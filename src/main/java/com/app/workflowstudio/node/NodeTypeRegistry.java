package com.app.workflowstudio.node;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 스프링 빈으로 등록된 {@link NodeModule}을 모아 id로 조회할 수 있게 한다. */
@Component
public class NodeTypeRegistry {

    private final Map<String, NodeModule> modules = new LinkedHashMap<>();

    // ObjectProvider: 등록된 모듈이 하나도 없어도 기동되도록 (@Order 순서 = 팔레트 순서)
    public NodeTypeRegistry(ObjectProvider<NodeModule> moduleBeans) {
        moduleBeans.orderedStream().forEach(m -> {
            String id = m.info().id();
            if (modules.putIfAbsent(id, m) != null) {
                throw new IllegalStateException("노드 타입 id 중복: " + id);
            }
        });
    }

    public List<NodeTypeInfo> getAll() {
        return modules.values().stream().map(NodeModule::info).toList();
    }

    public NodeModule find(String id) {
        return modules.get(id);
    }
}
