package com.univ.memoir.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.univ.memoir.api.dto.req.time.TimeAnalysisRequest;
import com.univ.memoir.api.dto.req.time.VisitedPageForTimeDto;
import com.univ.memoir.core.domain.DailySummary;
import com.univ.memoir.core.repository.DailySummaryRepository;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DailySummaryService {
	private static final Logger log = LoggerFactory.getLogger(DailySummaryService.class);

	private final WebClient openAiWebClient;
	private final ObjectMapper objectMapper;
	private final DailySummaryRepository dailySummaryRepository;

	private static final String OPENAI_MODEL = "gpt-3.5-turbo";
	private static final String OPENAI_URI = "/chat/completions";

	public Mono<DailySummaryResult> summarizeDay(String accessToken, TimeAnalysisRequest request) {
		List<VisitedPageForTimeDto> pages = request.getVisitedPages();
		if (pages == null || pages.isEmpty()) {
			return Mono.error(new IllegalArgumentException("방문 기록이 없습니다."));
		}

		LocalDate localDate = LocalDate.parse(request.getDate()); // "yyyy-MM-dd"

		return fetchCategoriesFromGPT(pages)
			.flatMap(categorizedPages -> {
				DailyActivityStats stats = calculateStats(categorizedPages);
				return fetchDailySummaryFromGPT(request.getDate(), categorizedPages)
					.map(gptSummary -> {
						DailySummaryResult result = new DailySummaryResult(
							request.getDate(),
							gptSummary.topKeywords,
							gptSummary.dailyTimeline,
							gptSummary.summaryText,
							new DailySummaryResult.ActivityStats(
								stats.totalUsageMinutes,
								stats.getCategoryPercentages()
							)
						);

						try {
							// DB 저장
							dailySummaryRepository.save(new DailySummary(
								localDate,
								objectMapper.writeValueAsString(result.topKeywords()),
								objectMapper.writeValueAsString(result.dailyTimeline()),
								objectMapper.writeValueAsString(result.summaryText()),
								result.activityStats().totalUsageTimeMinutes(),
								objectMapper.writeValueAsString(result.activityStats().activityProportions())
							));
						} catch (JsonProcessingException e) {
							throw new RuntimeException("DB 저장용 JSON 직렬화 실패", e);
						}

						return result;
					});
			});
	}
	private Mono<List<CategorizedPage>> fetchCategoriesFromGPT(List<VisitedPageForTimeDto> pages) {
		String prompt;
		try {
			String pagesJson = objectMapper.writeValueAsString(pages);
			prompt = """
                    아래는 사용자의 방문 기록입니다. 각 페이지의 제목과 URL을 참고하여 해당 페이지의 카테고리를 분류하세요.
                    카테고리는 다음 중 하나로만 정하세요:
                    '공부, 학습', '뉴스, 정보 탐색', '콘텐츠 소비', '쇼핑', '업무, 프로젝트'

                    다음 형식으로만 응답하세요 (JSON strict array):
                    [
                      { "title": "...", "url": "...", "category": "..." },
                      ...
                    ]

                    방문 기록:
                    %s
                    """.formatted(pagesJson);
		} catch (JsonProcessingException e) {
			return Mono.error(e);
		}

		Map<String, Object> body = Map.of(
			"model", OPENAI_MODEL,
			"messages", List.of(
				Map.of("role", "system", "content", "당신은 인터넷 기록 분류 전문가입니다."),
				Map.of("role", "user", "content", prompt)
			),
			"temperature", 0.2
		);

		return openAiWebClient.post()
			.uri(OPENAI_URI)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.retrieve()
			.bodyToMono(Map.class)
			.flatMap(response -> {
				try {
					List<?> choices = (List<?>) response.get("choices");
					Map<String, Object> message = (Map<String, Object>) ((Map<?, ?>) choices.get(0)).get("message");
					String content = Objects.toString(message.get("content"), "").trim();

					List<Map<String, String>> parsedList = objectMapper.readValue(content, List.class);
					if (parsedList.size() != pages.size()) {
						return Mono.error(new RuntimeException("카테고리 분류 결과 크기가 요청 데이터와 다릅니다."));
					}

					List<CategorizedPage> result = new ArrayList<>();
					for (int i = 0; i < pages.size(); i++) {
						String category = parsedList.get(i).getOrDefault("category", "분류불가");
						result.add(new CategorizedPage(pages.get(i), category));
					}
					return Mono.just(result);
				} catch (Exception e) {
					return Mono.error(new RuntimeException("GPT 응답 파싱 실패: " + e.getMessage(), e));
				}
			});
	}

	private DailyActivityStats calculateStats(List<CategorizedPage> pages) {
		int totalSeconds = 0;
		Map<String, Integer> categoryToSeconds = new HashMap<>();

		for (CategorizedPage page : pages) {
			int duration = page.page.getDurationSeconds();
			totalSeconds += duration;
			categoryToSeconds.merge(page.category, duration, Integer::sum);
		}

		return new DailyActivityStats(totalSeconds / 60, categoryToSeconds);
	}

	private Mono<GptSummary> fetchDailySummaryFromGPT(String date, List<CategorizedPage> pages) {
		StringBuilder visitSummary = new StringBuilder();
		for (CategorizedPage cp : pages) {
			visitSummary.append(String.format("- 제목: %s, 카테고리: %s%n", cp.page.getTitle(), cp.category));
		}

		String prompt = """
                당신은 디지털 활동 요약 전문가입니다.
                사용자가 %s 하루 동안 다음과 같은 인터넷 방문 기록과 카테고리 정보를 보냈습니다:

                %s

                위 데이터를 참고해 다음을 작성해주세요.
                1) 오늘의 키워드 상위 3개 (내림차순, { "keyword": "...", "frequency": 숫자 } JSON 배열 형식)
                2) 시간대별 활동 타임라인 (ex: "09:00 - 뉴스 읽기")
                3) 3줄짜리 전체 활동 요약 문장 (한국어)

                JSON 형식으로 아래 필드를 포함하여 응답하세요:
                {
                  "topKeywords": [ { "keyword": "...", "frequency": 숫자 }, ... ],
                  "dailyTimeline": [ { "time": "HH:mm", "description": "..." }, ... ],
                  "summaryText": [ "문장1", "문장2", "문장3" ]
                }
                """.formatted(date, visitSummary);

		Map<String, Object> body = Map.of(
			"model", OPENAI_MODEL,
			"messages", List.of(
				Map.of("role", "system", "content", "당신은 친절한 일일 활동 요약 전문가입니다."),
				Map.of("role", "user", "content", prompt)
			),
			"temperature", 0.3
		);

		return openAiWebClient.post()
			.uri(OPENAI_URI)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.retrieve()
			.bodyToMono(Map.class)
			.flatMap(response -> {
				try {
					List<?> choices = (List<?>) response.get("choices");
					Map<String, Object> message = (Map<String, Object>) ((Map<?, ?>) choices.get(0)).get("message");
					String content = Objects.toString(message.get("content"), "").trim();

					Map<String, Object> parsed = objectMapper.readValue(content, Map.class);

					List<Map<String, Object>> keywordsRaw = (List<Map<String, Object>>) parsed.get("topKeywords");
					List<DailySummaryResult.TopKeyword> keywords = keywordsRaw.stream()
						.map(k -> new DailySummaryResult.TopKeyword(
							Objects.toString(k.get("keyword"), ""),
							((Number) k.get("frequency")).intValue()))
						.toList();

					List<Map<String, Object>> timelineRaw = (List<Map<String, Object>>) parsed.get("dailyTimeline");
					List<DailySummaryResult.DailyTimelineEntry> timeline = timelineRaw.stream()
						.map(t -> new DailySummaryResult.DailyTimelineEntry(
							Objects.toString(t.get("time"), ""),
							Objects.toString(t.get("description"), "")))
						.toList();

					List<String> summaryText = (List<String>) parsed.getOrDefault("summaryText", List.of());

					return Mono.just(new GptSummary(keywords, timeline, summaryText));
				} catch (Exception e) {
					return Mono.error(new RuntimeException("GPT 일일 요약 파싱 실패: " + e.getMessage(), e));
				}
			});
	}

	private static class CategorizedPage {
		VisitedPageForTimeDto page;
		String category;

		public CategorizedPage(VisitedPageForTimeDto page, String category) {
			this.page = page;
			this.category = category;
		}
	}

	private static class DailyActivityStats {
		int totalUsageMinutes;
		Map<String, Integer> categorySeconds;

		public DailyActivityStats(int totalUsageMinutes, Map<String, Integer> categorySeconds) {
			this.totalUsageMinutes = totalUsageMinutes;
			this.categorySeconds = categorySeconds;
		}

		public List<DailySummaryResult.ActivityProportion> getCategoryPercentages() {
			List<DailySummaryResult.ActivityProportion> list = new ArrayList<>();
			if (totalUsageMinutes == 0) return list;

			for (Map.Entry<String, Integer> e : categorySeconds.entrySet()) {
				int percent = (int) Math.round((e.getValue() / 60.0) * 100 / totalUsageMinutes);
				list.add(new DailySummaryResult.ActivityProportion(e.getKey(), percent));
			}
			return list;
		}
	}

	private static class GptSummary {
		List<DailySummaryResult.TopKeyword> topKeywords;
		List<DailySummaryResult.DailyTimelineEntry> dailyTimeline;
		List<String> summaryText;

		public GptSummary(List<DailySummaryResult.TopKeyword> topKeywords,
			List<DailySummaryResult.DailyTimelineEntry> dailyTimeline,
			List<String> summaryText) {
			this.topKeywords = topKeywords;
			this.dailyTimeline = dailyTimeline;
			this.summaryText = summaryText;
		}
	}

	public static record DailySummaryResult(
		String date,
		List<TopKeyword> topKeywords,
		List<DailyTimelineEntry> dailyTimeline,
		List<String> summaryText,
		ActivityStats activityStats
	) {
		public record TopKeyword(String keyword, int frequency) {}
		public record DailyTimelineEntry(String time, String description) {}
		public record ActivityStats(int totalUsageTimeMinutes, List<ActivityProportion> activityProportions) {}
		public record ActivityProportion(String category, int percentage) {}
	}
}
