package com.example.model;

import java.io.Serializable;

public class LoanApplication implements Serializable {
    private static final long serialVersionUID = 1L;

    private String applicantId;
    private boolean approved;
    private String explanation;

    public LoanApplication() {
    }

    public LoanApplication(String applicantId) {
        this.applicantId = applicantId;
    }

    public String getApplicantId() {
        return applicantId;
    }

    public void setApplicantId(String applicantId) {
        this.applicantId = applicantId;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }
}
