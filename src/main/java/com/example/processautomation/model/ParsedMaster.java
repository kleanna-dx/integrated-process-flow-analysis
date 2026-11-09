package com.example.processautomation.model;

import java.util.List;

public record ParsedMaster(List<MasterEquipment> equipment, List<ParseIssue> issues) {
    public ParsedMaster {
        equipment = List.copyOf(equipment);
        issues = List.copyOf(issues);
    }

    public record ParseIssue(int sourceRowNumber, String severity, String code, String fieldName, String detail) {
    }
}
