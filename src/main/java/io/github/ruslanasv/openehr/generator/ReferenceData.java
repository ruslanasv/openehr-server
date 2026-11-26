package io.github.ruslanasv.openehr.generator;

import java.util.List;

/** Code lists the random clinical data is drawn from. */
public final class ReferenceData {

    public record Diagnosis(String icd10, String name) {}

    public record Drug(String atc, String name, int strengthMg, String form, String directions,
                       String timingInterval, String indication) {

        public String label() {
            return name + " " + strengthMg + " mg " + form.toLowerCase();
        }
    }

    public static final List<Diagnosis> DIAGNOSES = List.of(
            new Diagnosis("I10", "Essential (primary) hypertension"),
            new Diagnosis("E11.9", "Type 2 diabetes mellitus without complications"),
            new Diagnosis("E78.5", "Hyperlipidaemia, unspecified"),
            new Diagnosis("J45.9", "Asthma, unspecified"),
            new Diagnosis("K21.9", "Gastro-oesophageal reflux disease without oesophagitis"),
            new Diagnosis("F32.9", "Depressive episode, unspecified"),
            new Diagnosis("M54.5", "Low back pain"),
            new Diagnosis("J06.9", "Acute upper respiratory infection, unspecified"),
            new Diagnosis("I48.9", "Atrial fibrillation and atrial flutter, unspecified"),
            new Diagnosis("E03.9", "Hypothyroidism, unspecified"),
            new Diagnosis("N39.0", "Urinary tract infection, site not specified"),
            new Diagnosis("I25.1", "Atherosclerotic heart disease"));

    public static final List<Drug> DRUGS = List.of(
            new Drug("A10BA02", "Metformin", 500, "Tablet", "1 tablet twice daily with meals", "PT12H", "Type 2 diabetes mellitus"),
            new Drug("C09AA03", "Lisinopril", 10, "Tablet", "1 tablet once daily in the morning", "P1D", "Essential hypertension"),
            new Drug("C08CA01", "Amlodipine", 5, "Tablet", "1 tablet once daily", "P1D", "Essential hypertension"),
            new Drug("C10AA05", "Atorvastatin", 20, "Tablet", "1 tablet once daily in the evening", "P1D", "Hyperlipidaemia"),
            new Drug("C07AB07", "Bisoprolol", 5, "Tablet", "1 tablet once daily", "P1D", "Atrial fibrillation"),
            new Drug("A02BC01", "Omeprazole", 20, "Capsule", "1 capsule once daily before breakfast", "P1D", "Gastro-oesophageal reflux disease"),
            new Drug("N06AB06", "Sertraline", 50, "Tablet", "1 tablet once daily", "P1D", "Depressive episode"),
            new Drug("M01AE01", "Ibuprofen", 400, "Tablet", "1 tablet up to three times daily after meals", "PT8H", "Low back pain"),
            new Drug("N02BE01", "Paracetamol", 500, "Tablet", "1-2 tablets up to four times daily", "PT6H", "Pain or fever"),
            new Drug("J01CA04", "Amoxicillin", 500, "Capsule", "1 capsule three times daily for 7 days", "PT8H", "Urinary tract infection"));

    public static final List<String> CLINICIANS =
            List.of("Dr. Jonas Jonaitis", "Dr. Asta Petraitienė", "Dr. Tomas Kazlauskas", "Nurse Ona Onaitė");

    public static final List<String> FACILITIES =
            List.of("Vilnius Clinic", "Kaunas Health Centre", "Klaipėda Family Clinic");

    private ReferenceData() {}
}
