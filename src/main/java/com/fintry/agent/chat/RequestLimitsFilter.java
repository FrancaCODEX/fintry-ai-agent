package com.fintry.agent.chat;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class RequestLimitsFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    var path = req.getRequestURI().substring(req.getContextPath().length());
    if (path.startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
    if (!req.getMethod().equals("POST") || !path.equals("/api/chat")) {
      chain.doFilter(req, res);
      return;
    }
    byte[] bytes = req.getInputStream().readNBytes(8193);
    if (bytes.length > 8192) {
      res.setStatus(413);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"message\":\"Mensagem excede o limite de transporte de 8 KiB.\"}");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(req) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              public int read() {
                return input.read();
              }

              public boolean isFinished() {
                return input.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        res);
  }
}
