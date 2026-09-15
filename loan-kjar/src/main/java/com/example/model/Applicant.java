package com.example.model;

import java.io.Serializable;

public class Applicant implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private int age;

    public Applicant() {
    }

    public Applicant(String id, int age) {
        this.id = id;
        this.age = age;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }
}
