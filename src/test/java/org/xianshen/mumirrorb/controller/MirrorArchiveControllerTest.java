package org.xianshen.mumirrorb.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.pojo.VO.SnapshotPageVO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.MirrorService;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MirrorArchiveControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private MirrorService mirrorService;

    @Mock
    private ChatService chatService;

    private MirrorController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), "test"));
        controller = new MirrorController(mirrorService, chatService, new MirrorProperties());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void snapshotsDefaultsToTwentyAndPassesCursor() {
        OffsetDateTime cursorAt = OffsetDateTime.parse("2026-09-10T01:02:03.123456+08:00");
        when(mirrorService.listSnapshots(USER_ID, cursorAt, 17L, 20)).thenReturn(SnapshotPageVO.builder().build());

        controller.snapshots(cursorAt, 17L, null);

        verify(mirrorService).listSnapshots(USER_ID, cursorAt, 17L, 20);
    }

    @Test
    void snapshotsRejectsIncompleteCursorAndOutOfRangeLimit() {
        BusinessException incomplete = assertThrows(BusinessException.class,
                () -> controller.snapshots(OffsetDateTime.now(), null, null));
        assertEquals(400, incomplete.getCode());

        BusinessException tooMany = assertThrows(BusinessException.class,
                () -> controller.snapshots(null, null, 101));
        assertEquals(400, tooMany.getCode());

        BusinessException zero = assertThrows(BusinessException.class,
                () -> controller.snapshots(null, null, 0));
        assertEquals(400, zero.getCode());
    }
}
