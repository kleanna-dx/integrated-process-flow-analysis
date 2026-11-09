package com.example.processautomation.material;

import com.example.processautomation.excel.EquipmentBoxProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

/** Read-only, version-controlled profile catalog. No DB schema or mutable runtime state is involved. */
@Repository
public class EquipmentBoxProfileRepository {
    private final List<EquipmentBoxProfile> profiles;

    public EquipmentBoxProfileRepository(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource("equipment-box-profiles.json").getInputStream()) {
            Catalog catalog = objectMapper.readValue(input, Catalog.class);
            if (catalog == null || catalog.profiles() == null) throw new IllegalStateException("Profile catalog is empty");
            profiles = List.copyOf(catalog.profiles());
            long distinct = profiles.stream().map(EquipmentBoxProfile::profileId).distinct().count();
            if (distinct != profiles.size()) throw new IllegalStateException("Duplicate equipment box profileId");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load immutable equipment box profile catalog", exception);
        }
    }

    public List<EquipmentBoxProfile> findAll() { return profiles; }

    public Optional<EquipmentBoxProfile> findFor(String processCode, String sheetName, String sourceSha256) {
        return profiles.stream().filter(profile -> profile.appliesTo(processCode, sheetName, sourceSha256)).findFirst();
    }

    public Optional<EquipmentBoxProfile> findExact(String profileId, String profileVersion,
                                                   String processCode, String sheetName, String sourceSha256) {
        return findFor(processCode, sheetName, sourceSha256)
                .filter(profile -> profile.profileId().equals(profileId)
                        && profile.profileVersion().equals(profileVersion));
    }

    public List<EquipmentBoxProfile> findForSheet(String processCode, String sheetName) {
        return profiles.stream().filter(profile -> processCode.equals(profile.processCode())
                && sheetName.equals(profile.sheetName())).toList();
    }

    private record Catalog(int schemaVersion, List<EquipmentBoxProfile> profiles) { }
}
