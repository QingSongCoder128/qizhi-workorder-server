package com.qizhi.message.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.result.R;
import com.qizhi.message.entity.DeadLetter;
import com.qizhi.message.mapper.DeadLetterMapper;
import com.qizhi.message.mapper.DelayedTaskMapper;
import com.qizhi.message.mapper.SysMessageMapper;
import com.qizhi.message.producer.MessageProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MessageControllerTest {

    private MessageProducer producer;
    private DeadLetterMapper deadLetterMapper;
    private MessageController controller;

    @BeforeEach
    void setUp() {
        producer = mock(MessageProducer.class);
        deadLetterMapper = mock(DeadLetterMapper.class);
        controller = new MessageController(
                producer,
                mock(SysMessageMapper.class),
                deadLetterMapper,
                mock(DelayedTaskMapper.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(controller, "dlqMaxRetry", 3);
    }

    @Test
    void validDeadLetterIsRepublishedAndResolved() {
        DeadLetter letter = letter("{\"receiverId\":3,\"title\":\"retry\"}", 0);
        when(deadLetterMapper.selectById(7L)).thenReturn(letter);

        R<Void> result = controller.deadLetterRetry(7L);

        assertEquals(200, result.getCode());
        assertEquals("RESOLVED", letter.getStatus());
        assertEquals(1, letter.getRetryCount());
        @SuppressWarnings("unchecked")
        var sent = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(producer).sendNotify(sent.capture());
        assertEquals("3", sent.getValue().get("receiverId").toString());
        verify(deadLetterMapper).updateById(letter);
    }

    @Test
    void malformedDeadLetterEventuallyBecomesFailed() {
        DeadLetter letter = letter("{\"title\":\"missing receiver\"}", 2);
        when(deadLetterMapper.selectById(9L)).thenReturn(letter);

        R<Void> result = controller.deadLetterRetry(9L);

        assertTrue(result.getCode() != 200);
        assertEquals("FAILED", letter.getStatus());
        assertEquals(3, letter.getRetryCount());
        verifyNoInteractions(producer);
        verify(deadLetterMapper).updateById(letter);
    }

    @Test
    void retryAtLimitDoesNotPublishAgain() {
        DeadLetter letter = letter("{\"receiverId\":3,\"title\":\"retry\"}", 3);
        when(deadLetterMapper.selectById(11L)).thenReturn(letter);

        R<Void> result = controller.deadLetterRetry(11L);

        assertTrue(result.getCode() != 200);
        assertEquals("FAILED", letter.getStatus());
        verifyNoInteractions(producer);
    }

    private DeadLetter letter(String body, int retryCount) {
        DeadLetter letter = new DeadLetter();
        letter.setId(7L);
        letter.setMessageBody(body);
        letter.setRetryCount(retryCount);
        letter.setStatus("UNRESOLVED");
        return letter;
    }
}
