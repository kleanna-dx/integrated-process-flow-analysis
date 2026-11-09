package com.example.processautomation.model;

import java.util.Map;

public record MasterEquipment(
        int sourceRowNumber,
        int masterOrder,
        String permitManagementNo,
        String businessItemNo,
        String middleProcessCode,
        String majorProcessCode,
        String processCategory,
        String facilityName,
        String permitName,
        String permitCategory,
        String capacityText,
        String capacityUnit,
        String quantityText,
        String operatingHoursText,
        String annualOperatingDaysText,
        String purposeText,
        String wasteType,
        String wasteQuantityText,
        String wasteDestination,
        String wastewaterType,
        String wastewaterQuantityText,
        String wastewaterDestination,
        String pidNumber,
        String closedStatus,
        String paperExistsStatus,
        Map<String, String> rawValues) {

    public MasterEquipment {
        rawValues = Map.copyOf(rawValues);
    }
}
