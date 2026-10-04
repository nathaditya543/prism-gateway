package com.prism.gateway.gateway;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OpenAI-compatible data plane. Writes straight to the servlet response so streamed chunks
 * are flushed to the client as they arrive from the provider.
 */
@RestController
public class ChatCompletionController {

    private final ChatGatewayService gateway;

    public ChatCompletionController(ChatGatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/v1/chat/completions")
    public void complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                         @RequestHeader(value = HttpHeaders.CACHE_CONTROL, required = false) String cacheControl,
                         @RequestBody(required = false) String body,
                         HttpServletResponse response) throws IOException {
        gateway.handle(authorization, body, cacheControl).writeTo(response);
    }
}
