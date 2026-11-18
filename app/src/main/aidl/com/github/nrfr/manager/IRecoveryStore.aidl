package com.github.nrfr.manager;

interface IRecoveryStore {
    void destroy() = 16777114;
    int uid() = 1;
    byte[] exchange(String scope, String operation, in byte[] input) = 2;
}
