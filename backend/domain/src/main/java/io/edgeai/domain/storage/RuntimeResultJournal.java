package io.edgeai.domain.storage;
import io.edgeai.domain.runtime.RuntimeResultAuthority;
public interface RuntimeResultJournal {
    void retainResult(RuntimeResultAuthority authority);
}
