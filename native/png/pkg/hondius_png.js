/* @ts-self-types="./hondius_png.d.ts" */
import * as wasm from "./hondius_png_bg.wasm";
import { __wbg_set_wasm } from "./hondius_png_bg.js";

__wbg_set_wasm(wasm);
wasm.__wbindgen_start();
export {
    PngImage, png_decode, png_encode
} from "./hondius_png_bg.js";
