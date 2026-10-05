package io.edgeai.domain.storage;
import io.edgeai.domain.stream.StreamCompletionAuthority;
public interface StreamCompletionJournal {
    void retainCompletion(StreamCompletionAuthority authority);
}
