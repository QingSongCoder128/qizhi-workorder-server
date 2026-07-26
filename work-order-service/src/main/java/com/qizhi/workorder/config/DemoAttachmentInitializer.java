package com.qizhi.workorder.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates the files referenced by the enterprise demo SQL.
 *
 * <p>The database is reset on the middleware VM while the backend runs on
 * Windows, so the SQL import cannot populate module-local attachment storage.
 * This initializer is explicitly controlled by Nacos and is idempotent.</p>
 */
@Slf4j
@Component
public class DemoAttachmentInitializer implements ApplicationRunner {

    private static final Map<String, String> DEMO_FILES = new LinkedHashMap<>();

    static {
        DEMO_FILES.put("projector_fault.jpg", "会议室投影仪故障现场");
        DEMO_FILES.put("ac_temperature.jpg", "空调出风口测温记录");
        DEMO_FILES.put("office_supplies_list.pdf", "本季度办公用品采购清单");
        DEMO_FILES.put("disk_usage.png", "服务器磁盘使用率监控");
        DEMO_FILES.put("idc_temperature_alarm.png", "机房温度告警");
        DEMO_FILES.put("annual_meeting_list.pdf", "年会物资采购清单");
        DEMO_FILES.put("scanner_fault_list.pdf", "扫码枪故障设备清单");
        DEMO_FILES.put("coffee_machine_compare.pdf", "咖啡机选型对比");
        DEMO_FILES.put("finance_office.jpg", "财务办公室现场");
        DEMO_FILES.put("visitor_machine.jpg", "访客机触摸屏故障");
    }

    @Value("${storage.attachment.root:work-order-service/uploads/work-order}")
    private String attachmentStorageRoot;

    @Value("${demo.attachments.initialize:false}")
    private boolean initializeDemoAttachments;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!initializeDemoAttachments) {
            return;
        }
        Path root = Paths.get(attachmentStorageRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        int created = 0;
        for (Map.Entry<String, String> entry : DEMO_FILES.entrySet()) {
            Path target = root.resolve(entry.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalStateException("演示附件路径越界: " + entry.getKey());
            }
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                continue;
            }
            writeDemoFile(target, entry.getValue());
            created++;
        }
        log.info("企业演示附件初始化完成，目录={}，新增={}，总数={}", root, created, DEMO_FILES.size());
    }

    private void writeDemoFile(Path target, String title) throws IOException {
        String extension = target.getFileName().toString()
                .substring(target.getFileName().toString().lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        if ("pdf".equals(extension)) {
            Files.write(target, createMinimalPdf(title), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }

        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(239, 245, 255));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(31, 78, 121));
            graphics.fillRoundRect(80, 80, 1120, 560, 32, 32);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 44));
            graphics.drawString("企智协同工单调度系统", 170, 260);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 36));
            graphics.drawString(title, 170, 360);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 25));
            graphics.drawString("企业演示附件 · 系统启动时自动生成", 170, 450);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, extension, target.toFile())) {
            throw new IOException("不支持的演示图片格式: " + extension);
        }
    }

    private byte[] createMinimalPdf(String title) {
        String safeTitle = title.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String text = "BT /F1 18 Tf 72 760 Td (Qizhi Workorder Demo Attachment) Tj "
                + "0 -32 Td (" + safeTitle + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                        + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + text.getBytes(StandardCharsets.ISO_8859_1).length
                        + " >>\nstream\n" + text + "\nendstream"
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "%PDF-1.4\n");
        int[] offsets = new int[objects.size() + 1];
        for (int index = 0; index < objects.size(); index++) {
            offsets[index + 1] = output.size();
            writeAscii(output, (index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n");
        }
        int xrefOffset = output.size();
        writeAscii(output, "xref\n0 " + (objects.size() + 1) + "\n");
        writeAscii(output, "0000000000 65535 f \n");
        for (int index = 1; index < offsets.length; index++) {
            writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[index]));
        }
        writeAscii(output, "trailer\n<< /Size " + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");
        return output.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }
}
