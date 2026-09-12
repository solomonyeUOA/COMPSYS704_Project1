"""Launcher validation without building or starting any runtime."""
import argparse
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("abs_project_runner", ROOT / "tools/project.py")
PROJECT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROJECT)


class DemoLauncherTest(unittest.TestCase):
    def settings(self, **changes):
        values = dict(demo_slowdown=5.0, headless=True, trace_visualisation=True,
                      order="PO0001|1|P1,S,60,40,2", order_count=2, reset_after=12.0)
        values.update(changes)
        return argparse.Namespace(**values)

    def test_factors(self):
        for value in ("1", "5", "10", "1.5"):
            self.assertEqual(PROJECT.demo_slowdown(value), float(value))
        for value in ("NaN", "inf", "-inf", "0", "-1", "0.9", "10.01", "x"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                PROJECT.demo_slowdown(value)

    def test_same_factor_in_all_six_runtimes(self):
        for name, _ in PROJECT.CONFIGS:
            options = PROJECT.runtime_options(name, self.settings())
            self.assertEqual([x for x in options if x.startswith("-Dabs.simulation.slowdown=")],
                             ["-Dabs.simulation.slowdown=5.0"])
            self.assertEqual("-Dabs.visualisation.trace=true" in options, name == "visualisation")
            self.assertEqual(any(x.startswith("-Dabs.pos.testOrder=") for x in options), name == "pos")

    def test_wall_clock_test_controls_not_scaled(self):
        options = PROJECT.runtime_options("pos", self.settings())
        self.assertIn("-Dabs.pos.testOrderDelayMillis=10000", options)
        self.assertIn("-Dabs.pos.testOrderIntervalMillis=4000", options)
        self.assertIn("-Dabs.pos.testResetDelayMillis=12000", options)

    def test_normal_explicit_factor(self):
        options = PROJECT.runtime_options("m2", self.settings(demo_slowdown=1.0))
        self.assertEqual(options, ["-Djava.awt.headless=true", "-Dabs.simulation.slowdown=1.0"])

    def test_fractional_factor_not_rounded_for_runtime(self):
        options = PROJECT.runtime_options("m2", self.settings(demo_slowdown=1.0000001))
        self.assertIn("-Dabs.simulation.slowdown=1.0000001", options)

    def test_invalid_cli_fails_before_build_or_launch(self):
        for arguments in (("run", "--demo-slowdown", "nan"),
                          ("run", "--demo-slowdown", "11"),
                          ("test", "--demo-slowdown", "5")):
            result = subprocess.run([sys.executable, str(ROOT / "tools/project.py"), *arguments],
                                    cwd=ROOT, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn("SystemJ 1/", result.stdout)
            self.assertNotIn("Started m2", result.stdout)


if __name__ == "__main__":
    unittest.main()
