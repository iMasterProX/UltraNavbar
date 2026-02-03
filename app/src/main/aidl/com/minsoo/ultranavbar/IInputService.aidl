package com.minsoo.ultranavbar;

interface IInputService {
    void destroy() = 16777114;
    int injectKeyEvent(int keyCode, int metaState) = 1;
    int injectKeyCombo(in int[] keyCodes) = 2;
}
