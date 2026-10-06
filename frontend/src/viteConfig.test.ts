// @vitest-environment node
import { afterEach, describe, expect, it } from "vitest";
import type { ConfigEnv, UserConfigFnObject } from "vite";
import config from "../vite.config";

const productionBuild: ConfigEnv = {
  command: "build",
  mode: "production",
  isSsrBuild: false,
  isPreview: false,
};

describe("vite.config operator gate", () => {
  afterEach(() => {
    delete process.env.VITE_OPERATOR_LLPIN;
  });

  it("refuses a malformed LLPIN, naming the variable", () => {
    process.env.VITE_OPERATOR_LLPIN = "TBD";
    expect(() => (config as UserConfigFnObject)(productionBuild)).toThrow(
      /VITE_OPERATOR_LLPIN/,
    );
  });

  it("returns a config when the LLPIN is unset", () => {
    expect((config as UserConfigFnObject)(productionBuild)).toHaveProperty(
      "plugins",
    );
  });
});
