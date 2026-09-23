package com.bluffgame.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebPagesTest {

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    @Test
    void thePageCarriesLinkPreviewTagsWithAbsoluteUrls() {
        ResponseEntity<String> response = rest.getForEntity("/?room=ABC123", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody())
                .contains("property=\"og:image\" content=\"http://localhost:" + port + "/og-image.png\"")
                .contains("name=\"description\"")
                .contains("By Dhrushaj Achar")
                .doesNotContain("{{origin}}");
    }

    @Test
    void forwardedHeadersFromATunnelShapeThePreviewUrls() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-Proto", "https");
        headers.set("X-Forwarded-Host", "hebrew-dsc-friends-directions.trycloudflare.com");

        ResponseEntity<String> response = rest.exchange("/", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getBody())
                .contains("https://hebrew-dsc-friends-directions.trycloudflare.com/og-image.png");
    }

    @Test
    void thePreviewImageIsARealPng() {
        ResponseEntity<byte[]> response = rest.getForEntity("/og-image.png", byte[].class);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.length).isGreaterThan(10_000);
        assertThat(new byte[] {body[0], body[1], body[2], body[3]})
                .containsExactly((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }
}
