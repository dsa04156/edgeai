package io.edgeai.domain.storage;
import io.edgeai.domain.vd.VDTaskResultAuthority;
public interface VDTaskResultJournal {
    void retainVDResult(VDTaskResultAuthority authority);
}
