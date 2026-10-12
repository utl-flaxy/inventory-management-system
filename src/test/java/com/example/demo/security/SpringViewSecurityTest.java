package com.example.demo.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.view.xslt.XsltView;

import javax.xml.transform.Source;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Library regression fixtures only; the application does not enable SSE views or XSLT. */
class SpringViewSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void fragmentContentCannotInjectAnSseEvent(String separator) throws Exception {
        var request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(
                WebAsyncUtils.createAsyncWebRequest(request, response));
        View fixtureView = (model, req, res) -> res.getWriter().write(model.get("text").toString());
        var handler = new ResponseBodyEmitterReturnValueHandler(List.of(new StringHttpMessageConverter()),
                new ReactiveAdapterRegistry(), new SyncTaskExecutor(), new ContentNegotiationManager(),
                List.of((name, locale) -> fixtureView), new AcceptHeaderLocaleResolver());
        var emitter = new SseEmitter();
        var returnType = new MethodParameter(FixtureController.class.getDeclaredMethod("stream"), -1);
        handler.handleReturnValue(emitter, returnType, new ModelAndViewContainer(), new ServletWebRequest(request, response));
        emitter.send(SseEmitter.event().data(new ModelAndView("fixture",
                Map.of("text", "safe" + separator + "event:injected"))));
        assertEquals("event:fixture\ndata:safe\ndata:event:injected\n\n", response.getContentAsString());
        emitter.complete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.xsl", "../../outside.xsl", "file:/outside.xsl"})
    void unsafeStylesheetPathIsRejectedBeforeResourceAccess(String location) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getResource(anyString())).thenReturn(new ByteArrayResource("unused".getBytes(StandardCharsets.UTF_8)) {
            @Override public URI getURI() { return URI.create("memory:fixture"); }
        });
        var view = new InspectableXsltView();
        view.setCacheTemplates(false);
        view.setUrl(location);
        view.setApplicationContext(context);
        assertThrows(ApplicationContextException.class, view::stylesheet);
        verify(context, never()).getResource(anyString());
    }

    static class FixtureController {
        public SseEmitter stream() { return new SseEmitter(); }
    }

    static class InspectableXsltView extends XsltView {
        Source stylesheet() { return super.getStylesheetSource(); }
    }
}
