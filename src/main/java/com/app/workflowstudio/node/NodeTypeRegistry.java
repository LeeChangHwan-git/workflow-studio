package com.app.workflowstudio.node;

import com.app.workflowstudio.node.custom.CustomModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 노드 타입 등록부 — 스프링 빈으로 등록된 기본 {@link NodeModule} + 사용자가 만든 내 모듈({@link CustomModuleService}).
 * 내 모듈은 만들고·고치고·지울 때마다 바로 반영 (매번 서비스에서 읽음).
 */
@Component
public class NodeTypeRegistry {

    private final Map<String, NodeModule> modules = new LinkedHashMap<>();
    /** 내 모듈 서비스는 실행 엔진을 쓰고, 실행 엔진은 이 등록부를 써서 — 쓸 때 꺼내 순환 참조를 피함 */
    private final ObjectProvider<CustomModuleService> custom;

    // ObjectProvider: 등록된 모듈이 하나도 없어도 기동되도록 (@Order 순서 = 팔레트 순서)
    public NodeTypeRegistry(ObjectProvider<NodeModule> moduleBeans, ObjectProvider<CustomModuleService> custom) {
        this.custom = custom;
        moduleBeans.orderedStream().forEach(m -> {
            String id = m.info().id();
            if (modules.putIfAbsent(id, m) != null) {
                throw new IllegalStateException("노드 타입 id 중복: " + id);
            }
        });
    }

    public List<NodeTypeInfo> getAll() {
        List<NodeTypeInfo> all = new ArrayList<>(modules.values().stream().map(NodeModule::info).toList());
        CustomModuleService c = custom.getIfAvailable();
        if (c != null) c.modules().forEach(m -> all.add(m.info()));
        return all;
    }

    public NodeModule find(String id) {
        NodeModule m = modules.get(id);
        if (m != null || id == null) return m;
        CustomModuleService c = custom.getIfAvailable();
        return c == null ? null : c.find(id);
    }

    /** 앱에 들어 있는 기본 모듈만 */
    public NodeModule builtin(String id) {
        return modules.get(id);
    }

    public List<NodeTypeInfo> builtins() {
        return modules.values().stream().map(NodeModule::info).toList();
    }
}
