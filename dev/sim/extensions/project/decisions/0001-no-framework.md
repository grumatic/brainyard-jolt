# 0001 — No framework

Status: accepted

The server is node:http and nothing else: no express, koa, fastify, hapi or other
framework, and no router library. Its dependencies are ws and node-pty, and a
new one needs a decision of its own first.

Why: the workspace is small, and every dependency is one more thing to audit.
