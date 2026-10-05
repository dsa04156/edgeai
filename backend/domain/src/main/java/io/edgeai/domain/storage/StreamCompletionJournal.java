package io.edgeai.domain.storage;
import io.edgeai.domain.stream.StreamCompletionAuthority;
import io.edgeai.domain.stream.StreamCheckpointAuthority;
public interface StreamCompletionJournal {
    void retainCompletion(StreamCompletionAuthority authority);
    void retainCheckpoint(StreamCheckpointAuthority authority);
}
