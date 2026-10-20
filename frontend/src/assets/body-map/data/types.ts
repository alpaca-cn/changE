// Vendored from vulovix/body-muscles @ 15c8085ee97cb94c51f92c75224416b15f955b5c
// Apache-2.0 — see LICENSE and NOTICE in this directory.
// 原样 vendor，除本注释外未修改。用途：changE 人体图的 2D 静态热力图。
import { MuscleId, ViewSide } from "../types";

/**
 * SVG path definition for a muscle region
 */
export interface MuscleDef {
  /** Unique muscle identifier */
  id: MuscleId;
  /** Display name (e.g., "Left Biceps", "Right Front Shoulder") */
  name: string;
  /** SVG path data for the 'd' attribute */
  path: string;
  /** Which anatomical view this muscle belongs to */
  view: ViewSide;
}
