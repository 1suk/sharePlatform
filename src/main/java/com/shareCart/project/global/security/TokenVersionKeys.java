package com.shareCart.project.global.security;

import lombok.*;

@NoArgsConstructor
public class TokenVersionKeys {

    private static final String PREFIX = "TV:";

    public static String key(String email){
        return PREFIX + email;
    }
}
