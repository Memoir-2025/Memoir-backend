package com.univ.memoir.core.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.univ.memoir.api.dto.res.DailyPopupResponse;
import com.univ.memoir.api.dto.res.MonthlySummaryResponse;
import com.univ.memoir.core.domain.DailySummary;
import com.univ.memoir.core.repository.DailySummaryRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MonthlySummaryService {

	private final DailySummaryRepository dailySummaryRepository;
	private final ObjectMapper objectMapper;

	public MonthlySummaryResponse.Data getMonthlySummary(YearMonth yearMonth) {
		LocalDate start = yearMonth.atDay(1);
		LocalDate end = yearMonth.atEndOfMonth();

		List<DailySummary> summaries = dailySummaryRepository.findAllByDateBetween(start, end);

		List<MonthlySummaryResponse.CalendarEntry> entries = summaries.stream()
			.map(summary -> {
				String title = extractTopKeyword(summary.getTopKeywordsJson());
				return new MonthlySummaryResponse.CalendarEntry(summary.getDate().toString(), title);
			})
			.toList();

		return new MonthlySummaryResponse.Data(
			yearMonth.getYear(),
			yearMonth.getMonthValue(),
			entries
		);
	}

	private String extractTopKeyword(String topKeywordsJson) {
		try {
			List<Map<String, Object>> list = objectMapper.readValue(topKeywordsJson, List.class);
			if (!list.isEmpty()) {
				return Objects.toString(list.get(0).get("keyword"), "기록 없음");
			}
		} catch (JsonProcessingException e) {
			return "기록 없음";
		}
		return "기록 없음";
	}

	public DailyPopupResponse.Data getDailyPopup(LocalDate date) {
		DailySummary summary = dailySummaryRepository.findByDate(date).orElseThrow(() -> new EntityNotFoundException("해당 날짜의 요약이 존재하지 않습니다."));
		List<String> summaryTexts = parseSummaryTextJson(summary.getSummaryTextJson());

		return new DailyPopupResponse.Data(date.toString(), summaryTexts);
	}
	private List<String> parseSummaryTextJson(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<String>>() {});
		} catch (JsonProcessingException e) {
			throw new RuntimeException("summaryTextJson 파싱 실패", e);
		}
	}
}
