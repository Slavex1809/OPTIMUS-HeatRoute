#!/usr/bin/env python3
"""Rebuild the canonical full-project.txt, checksums and ZIP."""
import pathlib,runpy
runpy.run_path(str(pathlib.Path(__file__).with_name('bundle.py')),run_name='__main__')
