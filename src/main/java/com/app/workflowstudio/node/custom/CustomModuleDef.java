package com.app.workflowstudio.node.custom;

import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 만든 모듈(내 모듈) 정의 — {storage}/modules/{id}.json, 내보내기 파일(.wbmod) 안의 "module".
 * <ul>
 *   <li>{@code kind = "http"}: HTTP 요청 하나를 입력칸만 보이는 전용 노드로 포장 (코드 없이)</li>
 *   <li>{@code kind = "pipeline"}: 만들어 둔 파이프라인을 노드 하나로 포장 (안쪽 노드를 그대로 실행)</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomModuleDef {

    public static final String KIND_HTTP = "http";
    public static final String KIND_PIPELINE = "pipeline";

    /** 노드 타입 id (예: MOD_1a2b3c4d) — 파이프라인 파일에 이 id 로 저장됨 */
    public String id;
    public String kind = KIND_HTTP;
    public String label = "";
    public String icon = "🧩";
    public String color = "#7C3AED";
    public String category = "내 모듈";
    public String description = "";
    /** 사용자가 올리는 버전 (내보내기 파일에 표시) */
    public String version = "1.0";
    public String author = "";
    public String createdAt;
    public String updatedAt;
    /** 노드 설정 화면의 입력칸 */
    public List<Param> params = new ArrayList<>();
    /** 출력 변수명 기본값 */
    public String outputVar = "";

    public Http http;
    public Sub pipeline;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Param {
        /** 템플릿에서 {{name}} 으로 씀 (공백 없이) */
        public String name = "";
        public String label = "";
        /** text | textarea | number | select | password */
        public String type = "text";
        public String defaultValue = "";
        public List<String> options = new ArrayList<>();
        /** 파이프라인 모듈: 이 입력값을 넣을 안쪽 노드 설정 (없으면 ${in.이름} 변수로만) */
        public Target target;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {
        public String nodeId;
        public String key;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Http {
        public String method = "POST";
        /** {{입력}} 은 URL 인코딩되어 들어감 */
        public String url = "";
        /** 한 줄에 "이름: 값" — {{입력}} 사용 가능 */
        public String headers = "";
        /** none | json | text | form */
        public String bodyType = "json";
        /** json 이면 "{{입력}}" 처럼 따옴표 안에 쓰면 JSON 안전하게 들어감 */
        public String body = "";
        public int timeoutSec = 30;
        /** 2xx 가 아니면 오류로 멈출지 */
        public boolean failOnError = true;
        /** 응답 JSON 에서 꺼낼 값 — ${출력변수.이름} */
        public List<Extract> extract = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extract {
        public String name = "";
        /** 예: data.docNo · items[0].title */
        public String path = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sub {
        /** 만든 원본 파이프라인 (다시 가져오기용 — 다른 PC 에서는 없을 수 있음) */
        public String sourcePipelineId;
        public String sourceName;
        /** 결과로 쓸 안쪽 노드 (비우면 마지막으로 출력을 낸 노드) */
        public String outputNodeId;
        /** 안쪽 파이프라인 (트리거 노드는 빼고 저장) */
        public List<PipelineNode> nodes = new ArrayList<>();
        public List<PipelineConnection> connections = new ArrayList<>();
    }
}
