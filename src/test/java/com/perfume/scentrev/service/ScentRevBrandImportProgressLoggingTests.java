package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevBrandPhase1ImportServiceTests.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;

class ScentRevBrandImportProgressLoggingTests {
    @Test
    void reportsEveryFiftyAndTheFinalCountWithoutExtraRequestsOrFragranceNames() {
        var discovery = mock(ScentRevBrandDiscoveryService.class);
        var client = mock(ScentRevMcpClient.class);
        var mapper = mock(ScentRevPhase1ImportService.class);
        var fragrances = IntStream.rangeClosed(1, 105)
                .mapToObj(i -> new Fragrance("creed-" + i, "id-creed-" + i, "private-fragrance-name", "creed")).toList();
        when(discovery.discoverBrandFragrances("creed"))
                .thenReturn(new ScentRevBrandDiscoveryResult("creed", 11, 105, 0, fragrances));
        when(client.getFragranceProfile(any())).thenAnswer(call -> profile("creed", call.getArgument(0)));
        var logger = (Logger) LoggerFactory.getLogger(ScentRevBrandPhase1ImportService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = new ScentRevBrandPhase1ImportService(discovery, client, mapper).importBrand("creed");
            assertThat(result.successfullyProcessedCount()).isEqualTo(105);
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).containsExactly(
                    "Brand creed: 105 unique fragrances discovered", "Brand creed: 50 / 105 fragrances processed",
                    "Brand creed: 100 / 105 fragrances processed", "Brand creed: 105 / 105 fragrances processed");
            verify(discovery, times(1)).discoverBrandFragrances("creed");
            verify(client, times(105)).getFragranceProfile(any());
            verify(mapper, times(105)).importProfile(any());
            verifyNoMoreInteractions(discovery, client, mapper);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
