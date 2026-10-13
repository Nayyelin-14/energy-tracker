package com.energytracker.insightservice.dto;

public enum InsightType {
        OVERVIEW("Provide a concise overview of the user's energy usage. "
                        + "Highlight the highest-consuming devices based on the supplied data."),
        SAVING_TIPS("Give practical, prioritized ways to reduce energy consumption "
                        + "based on the supplied data.");

        private final String promptInstruction;

        InsightType(String promptInstruction) {
                this.promptInstruction = promptInstruction;
        }

        public String promptInstruction() {
                return promptInstruction;
        }
}
