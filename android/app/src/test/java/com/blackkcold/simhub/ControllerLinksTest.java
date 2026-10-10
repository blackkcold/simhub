package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControllerLinksTest {
    @Test public void acceptsOnlyHttpsManagementOrigins() {
        assertEquals("https://admin.example.com",ControllerLinks.INSTANCE.normalize("https://admin.example.com/"));
        assertEquals("https://admin.example.com:9443",ControllerLinks.INSTANCE.normalize("https://ADMIN.example.com:9443"));
        assertNull(ControllerLinks.INSTANCE.normalize("http://admin.example.com"));
        assertNull(ControllerLinks.INSTANCE.normalize("https://user:password@admin.example.com"));
        assertNull(ControllerLinks.INSTANCE.normalize("https://admin.example.com/path"));
        assertNull(ControllerLinks.INSTANCE.normalize("https://admin.example.com/?token=secret"));
        assertNull(ControllerLinks.INSTANCE.normalize("https://localhost"));
    }
    @Test public void controllerQrNeverContainsCredentials() {
        assertEquals("https://admin.example.com",ControllerLinks.INSTANCE.decode(
            "simhub://controller?url=https%3A%2F%2Fadmin.example.com"));
        assertNull(ControllerLinks.INSTANCE.decode("simhub://enroll?server=https%3A%2F%2Fadmin.example.com"));
        assertNull(ControllerLinks.INSTANCE.decode("simhub://controller?url=https%3A%2F%2Fadmin.example.com&token=abc"));
        assertNull(ControllerLinks.INSTANCE.decode("simhub://controller?url=https%3A%2F%2Fadmin.example.com%2F%3Fotp%3D123456"));
    }
}
