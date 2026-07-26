package com.qizhi.user.controller;

import com.qizhi.user.mapper.SysUserMapper;
import com.qizhi.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class UserControllerStorageTest {

    @Mock private UserService userService;
    @Mock private SysUserMapper userMapper;

    @InjectMocks
    private UserController controller;

    @TempDir
    Path tempDir;

    @Test
    void avatarIsStoredAndReadFromConfiguredUserServiceDirectory() throws Exception {
        ReflectionTestUtils.setField(controller, "avatarStorageRoot", tempDir.toString());
        MockMultipartFile avatar = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{9, 8, 7});

        String url = controller.uploadAvatar(1L, avatar).getData().get("url");
        String filename = url.substring(url.lastIndexOf('/') + 1);
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(tempDir.resolve(filename)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.getAvatar(filename, response);
        assertEquals(200, response.getStatus());
        assertArrayEquals(new byte[]{9, 8, 7}, response.getContentAsByteArray());
    }

    @Test
    void avatarPathTraversalIsRejected() {
        ReflectionTestUtils.setField(controller, "avatarStorageRoot", tempDir.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.getAvatar("../secret.png", response);

        assertEquals(400, response.getStatus());
    }
}
