package com.qqmu.jargus.service.version;

import com.qqmu.jargus.entity.UpdateArtifactDismissal;
import com.qqmu.jargus.mapper.UpdateArtifactDismissalMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 单行忽略记录的持久化分支（无行插入 / 有行更新 / 并发唯一键回退）。 */
@ExtendWith(MockitoExtension.class)
class DatabaseArtifactDismissalTest {

    @Mock
    UpdateArtifactDismissalMapper mapper;

    @InjectMocks
    DatabaseArtifactDismissal dismissal;

    @Test
    void insertsSingletonWhenNoRowExists() {
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(null);

        dismissal.dismiss("  sha256:new  ");

        ArgumentCaptor<UpdateArtifactDismissal> captor =
                ArgumentCaptor.forClass(UpdateArtifactDismissal.class);
        verify(mapper).insert(captor.capture());
        UpdateArtifactDismissal row = captor.getValue();
        assertEquals(UpdateArtifactDismissal.SINGLETON_ID, row.getId());
        assertEquals("sha256:new", row.getArtifactKey());
        assertNotNull(row.getDismissedAt());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void updatesExistingRow() {
        UpdateArtifactDismissal existing = new UpdateArtifactDismissal();
        existing.setId(UpdateArtifactDismissal.SINGLETON_ID);
        existing.setArtifactKey("sha256:old");
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(1);

        dismissal.dismiss("sha256:new");

        ArgumentCaptor<UpdateArtifactDismissal> captor =
                ArgumentCaptor.forClass(UpdateArtifactDismissal.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("sha256:new", captor.getValue().getArtifactKey());
        assertNotNull(captor.getValue().getDismissedAt());
        verify(mapper, never()).insert(any());
    }

    @Test
    void blankKeyDoesNothing() {
        dismissal.dismiss("   ");
        dismissal.dismiss(null);
        verifyNoInteractions(mapper);
    }

    @Test
    void duplicateKeyFallsBackToUpdate() {
        UpdateArtifactDismissal existing = new UpdateArtifactDismissal();
        existing.setId(UpdateArtifactDismissal.SINGLETON_ID);
        existing.setArtifactKey("sha256:old");
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any());
        when(mapper.updateById(any())).thenReturn(1);

        dismissal.dismiss("sha256:new");

        ArgumentCaptor<UpdateArtifactDismissal> captor =
                ArgumentCaptor.forClass(UpdateArtifactDismissal.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("sha256:new", captor.getValue().getArtifactKey());
    }

    @Test
    void writeFailurePropagatesToCaller() {
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> dismissal.dismiss("sha256:new"));
    }

    @Test
    void insertFailurePropagatesToCaller() {
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(null);
        doThrow(new IllegalStateException("disk full")).when(mapper).insert(any());

        assertThrows(IllegalStateException.class, () -> dismissal.dismiss("sha256:new"));
    }

    @Test
    void updateAffectingNoRowsIsFailure() {
        UpdateArtifactDismissal existing = new UpdateArtifactDismissal();
        existing.setId(UpdateArtifactDismissal.SINGLETON_ID);
        existing.setArtifactKey("sha256:old");
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> dismissal.dismiss("sha256:new"));
    }

    @Test
    void readFailureDegradesToNotDismissed() {
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID))
                .thenThrow(new IllegalStateException("table missing"));

        assertNull(dismissal.dismissedKey());
    }

    @Test
    void returnsStoredKey() {
        UpdateArtifactDismissal row = new UpdateArtifactDismissal();
        row.setId(UpdateArtifactDismissal.SINGLETON_ID);
        row.setArtifactKey("size:100");
        when(mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID)).thenReturn(row);

        assertEquals("size:100", dismissal.dismissedKey());
    }
}
