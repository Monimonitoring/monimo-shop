package com.monimo.shop.gateway

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

/**
 * 받은 요청을 뒤 서비스(주문 · 재고)로 그대로 넘기고, 응답(상태 코드 · 본문)을 바꾸지 않고 돌려준다.
 * Service map 의 gateway → order · gateway → inventory 화살표가 여기서 나온다. 어디로 보낼지는 GatewayController 가 경로로 정한다.
 */
@Component
class UpstreamProxy(private val restTemplate: RestTemplate) {
    fun forward(baseUrl: String, request: HttpServletRequest, body: ByteArray?): ResponseEntity<ByteArray> {
        val uri = UriComponentsBuilder.fromUriString(baseUrl)
            .path(request.requestURI)
            .query(request.queryString)
            .build(true) // 이미 인코딩된 경로 · 쿼리라 다시 인코딩하지 않는다
            .toUri()
        val headers = HttpHeaders().apply {
            FORWARD_HEADERS.forEach { name -> request.getHeader(name)?.let { set(name, it) } }
        }
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.valueOf(request.method), HttpEntity(body, headers), ByteArray::class.java)
            ResponseEntity.status(res.statusCode)
                .headers { h -> res.headers.contentType?.let { h.contentType = it } }
                .body(res.body)
        } catch (e: ResourceAccessException) {
            // 연결 실패 · 타임아웃. 뒤 서비스가 응답을 못 줬다는 뜻이라 502
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(UNAVAILABLE_BODY)
        }
    }

    companion object {
        /** 정한 헤더만 옮긴다. traceparent 는 에이전트가 자동으로 넣으므로 손대지 않는다. X-Shop-Fault 는 k6 장애 주입용 */
        val FORWARD_HEADERS = listOf(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, "X-Shop-Fault")
        private val UNAVAILABLE_BODY = """{"reason":"upstream unavailable"}""".toByteArray()
    }
}
