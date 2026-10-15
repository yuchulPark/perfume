package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class PerfumeEmbeddingBatchRunnerTests {
    @Test
    void reportsPartialFailureCountsAndCommittedCursorBeforeFailingTheCommand() {
        var batch = mock(PerfumeEmbeddingBatch.class);
        var options = PerfumeEmbeddingBatchTests.options(1000, 25, 0, PerfumeEmbeddingBatchTests.model());
        var result = new PerfumeEmbeddingBatch.Result(12, 12, 12, 3, 2, 6, 1, 20, 20, false, false);
        when(batch.execute(options)).thenReturn(result);
        var logger = (Logger) LoggerFactory.getLogger(PerfumeEmbeddingBatchRunner.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start(); logger.addAppender(output);
        try {
            assertThatThrownBy(() -> new PerfumeEmbeddingBatchRunner(batch, options).run(new DefaultApplicationArguments()))
                    .isInstanceOf(EmbeddingFailure.class).hasMessageContaining("PARTIAL_FAILURE").hasNoCause();
            assertThat(output.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("target=12, processed=12, new=3, changed=2, skipped=6, failed=1, lastId=20"));
        } finally { logger.detachAppender(output); output.stop(); }
    }

    @Test
    void globalFailureStillReportsAvailableProgressWithoutLeakingDiagnostics() {
        var batch = mock(PerfumeEmbeddingBatch.class);
        var options = PerfumeEmbeddingBatchTests.options(1000, 25, 0, PerfumeEmbeddingBatchTests.model());
        doThrow(new EmbeddingFailure(EmbeddingFailure.Code.DATABASE,
                new PerfumeEmbeddingBatch.Result(-1, -1, 0, 0, 0, 0, 0, 0, -1, false, false))).when(batch).execute(options);
        var logger = (Logger) LoggerFactory.getLogger(PerfumeEmbeddingBatchRunner.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start(); logger.addAppender(output);
        try {
            assertThatThrownBy(() -> new PerfumeEmbeddingBatchRunner(batch, options).run(new DefaultApplicationArguments()))
                    .isInstanceOf(EmbeddingFailure.class).hasMessageContaining("DATABASE").hasNoCause();
            assertThat(output.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("available=unavailable, target=unavailable, processed=0"))
                    .anyMatch(message -> message.contains("stopped: code=DATABASE"));
        } finally { logger.detachAppender(output); output.stop(); }
    }
}
