package com.bareum.server.domain.report.config;

import java.util.Set;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.WebUtils;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 보고서 접수 요청의 파일 파트 개수와 입력 필드를 검증하도록 설정한다. */
@Configuration(proxyBeanMethods = false)
public class ReportUploadWebConfig implements WebMvcConfigurer {
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new UploadPartsInterceptor()).addPathPatterns("/api/v1/reports");
	}

	/** 접수 요청에 PDF 파일 파트 하나와 문서 유형만 포함되었는지 확인한다. */
	static class UploadPartsInterceptor implements HandlerInterceptor {
		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			if (!"POST".equals(request.getMethod())) {
				return true;
			}
			var multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
			if (multipart == null) {
				throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
			}
			var files = multipart.getMultiFileMap();
			if (files.size() != 1 || files.get("file") == null || files.get("file").size() != 1) {
				throw new ReportException(ReportErrorCode.INVALID_FILE_COUNT);
			}
			if (!Set.of("reportType").containsAll(multipart.getParameterMap().keySet())
					|| (multipart.getParameterValues("reportType") != null
							&& multipart.getParameterValues("reportType").length != 1)) {
				throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
			}
			return true;
		}
	}
}
