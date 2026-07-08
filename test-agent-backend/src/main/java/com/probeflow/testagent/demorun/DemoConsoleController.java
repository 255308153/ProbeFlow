package com.probeflow.testagent.demorun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoConsoleController {

    private static final ClassPathResource CONSOLE_PAGE =
        new ClassPathResource("v4-demo-console/index.html");

    @GetMapping(value = "/v4/demo-console", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> console() throws IOException {
        var html = StreamUtils.copyToString(CONSOLE_PAGE.getInputStream(), StandardCharsets.UTF_8);
        return ResponseEntity.ok(html);
    }
}
