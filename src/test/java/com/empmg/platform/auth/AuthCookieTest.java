package com.empmg.platform.auth;

import com.empmg.platform.common.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AuthCookieTest {
    @Test
    void secureCookiesFollowTrustedHttpsConfigurationBehindTlsProxy() {
        var props = new AppProperties();
        var controller = new AuthController(null, null, null, null, null, props);
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        ResponseCookie local = ReflectionTestUtils.invokeMethod(controller, "refreshCookie", 1L, request, "token");
        assertNotNull(local);
        assertFalse(local.isSecure());
        props.setFrontendUrl("https://portal.example.test");
        ResponseCookie hosted = ReflectionTestUtils.invokeMethod(controller, "refreshCookie", 1L, request, "token");
        assertNotNull(hosted);
        assertTrue(hosted.isSecure());
        assertTrue(hosted.isHttpOnly());
        assertEquals("Strict", hosted.getSameSite());
    }
}
