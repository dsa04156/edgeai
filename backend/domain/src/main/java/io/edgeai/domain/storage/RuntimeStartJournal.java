package io.edgeai.domain.storage;
import io.edgeai.domain.runtime.RuntimeStartAuthority;

/** Must retain and reverify the first matching admission before the claim response can be sent. */
public interface RuntimeStartJournal {
    void retainStart(RuntimeStartAuthority authority);
}
