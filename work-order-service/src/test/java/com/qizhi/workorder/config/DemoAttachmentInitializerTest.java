package com.qizhi.workorder.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAttachmentInitializerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void initializesAllSqlReferencedDemoAttachmentsIdempotently() throws Exception {
        DemoAttachmentInitializer initializer = new DemoAttachmentInitializer();
        ReflectionTestUtils.setField(initializer, "attachmentStorageRoot", tempDirectory.toString());
        ReflectionTestUtils.setField(initializer, "initializeDemoAttachments", true);

        initializer.run(new DefaultApplicationArguments());
        long firstCount;
        try (var files = Files.list(tempDirectory)) {
            firstCount = files.count();
        }
        initializer.run(new DefaultApplicationArguments());
        long secondCount;
        try (var files = Files.list(tempDirectory)) {
            secondCount = files.count();
        }

        assertThat(firstCount).isEqualTo(10);
        assertThat(secondCount).isEqualTo(10);
        assertThat(Files.readString(tempDirectory.resolve("office_supplies_list.pdf"),
                StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
        assertThat(Files.size(tempDirectory.resolve("projector_fault.jpg"))).isGreaterThan(1_000);
        assertThat(Files.size(tempDirectory.resolve("disk_usage.png"))).isGreaterThan(1_000);
    }
}
