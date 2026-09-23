package com.bluffgame.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * Serves the single page with its link-preview tags filled in. Chat apps need absolute image
 * URLs, and the host name changes with every tunnel, so the page's {@code {{origin}}}
 * placeholder is replaced with the origin the request actually came in on.
 */
@Controller
public class IndexController {

    private static final String PLACEHOLDER = "{{origin}}";

    private final ResourceLoader resourceLoader;
    private final WebProperties webProperties;

    public IndexController(ResourceLoader resourceLoader, WebProperties webProperties) {
        this.resourceLoader = resourceLoader;
        this.webProperties = webProperties;
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index(HttpServletRequest request) throws IOException {
        String origin = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        String html = page().replace(PLACEHOLDER, HtmlUtils.htmlEscape(origin));
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .body(html);
    }

    private String page() throws IOException {
        for (String location : webProperties.getResources().getStaticLocations()) {
            Resource resource = resourceLoader.getResource(location + "index.html");
            if (resource.exists()) {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            }
        }
        throw new IOException("index.html is missing from the static resources");
    }
}
