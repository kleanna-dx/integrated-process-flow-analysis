package com.example.processautomation.excel;

import com.example.processautomation.model.MasterEquipment;
import org.springframework.stereotype.Service;

/** Thin generic adapter around the existing DrawingML insertion engine. */
@Service
public class Pw0105AutoEditService {
    private final Pw0105EquipmentInsertionEngine engine;

    public Pw0105AutoEditService(Pw0105EquipmentInsertionEngine engine) {
        this.engine = engine;
    }

    public Pw0105EquipmentInsertionEngine.EditResult generateTestOnly(
            byte[] immutableBaselineWorkbook, MasterEquipment equipment,
            Pw0105EquipmentInsertionEngine.TestEditSpec specification) {
        if (immutableBaselineWorkbook == null || immutableBaselineWorkbook.length == 0) {
            throw new InvalidWorkbookException("A read-only V1.0 baseline workbook is required.");
        }
        return engine.insertTestOnly(immutableBaselineWorkbook, equipment, specification);
    }
}
