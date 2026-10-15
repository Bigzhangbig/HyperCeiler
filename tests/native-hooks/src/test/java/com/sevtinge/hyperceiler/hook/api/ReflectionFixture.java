package com.sevtinge.hyperceiler.hook.api;

public class ReflectionFixture {
        private String value = "initial";
        public ReflectionFixture() {}
        public ReflectionFixture(int value) { this.value = String.valueOf(value); }
        private String value() { return value; }
        private String choose(Integer number) { return "integer"; }
        private String choose(Number number) { return "number"; }
        private String self(ReflectionFixture other) { return "self"; }
    }
