package io.edgeai.domain.storage;
import io.edgeai.domain.vd.VDTaskStartAuthority;

/** Retain the original admitted child/session/slot before returning executable work. */
public interface VDTaskStartJournal {
    void retainVDStart(VDTaskStartAuthority authority);
}
