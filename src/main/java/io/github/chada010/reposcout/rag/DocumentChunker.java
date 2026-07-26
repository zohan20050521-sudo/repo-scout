package io.github.chada010.reposcout.rag;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import io.github.chada010.reposcout.config.RagProperties;

/**
 * 文档切分(FR-3.1):用 langchain4j 递归字符切分器,对每篇文档按 chunkSize/chunkOverlap
 * 切成文本块,保留 file_path 与文件内递增 chunk_index。
 *
 * <p>默认 chunkSize=400、chunkOverlap=80:bge 输入上限 512 token,中文单字常 >1 token,
 * chunk 取偏小以免向量化时频繁截断丢信息。
 */
@Component
public class DocumentChunker {

    private final RagProperties props;

    public DocumentChunker(RagProperties props) {
        this.props = props;
    }

    /** 切一篇文档;chunk_index 在该文档内从 0 递增(不跨文档累加)。 */
    public List<Chunk> chunk(FetchedDocument doc) {
        DocumentSplitter splitter = DocumentSplitters.recursive(props.chunkSize(), props.chunkOverlap());
        Document document = Document.from(doc.content(), Metadata.from("file_path", doc.filePath()));
        List<TextSegment> segments = splitter.split(document);
        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            chunks.add(new Chunk(doc.filePath(), i, segments.get(i).text()));
        }
        return chunks;
    }
}
