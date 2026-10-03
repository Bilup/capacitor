#!/usr/bin/env node
/**
 * Refresh engine-lock.json from a scratch-gui checkout.
 *
 * A pin nobody can update is a pin that gets abandoned. After pushing
 * scratch-gui's develop, run this against your scratch-gui clone and commit the
 * result: it records the commit to build and the engine revisions that commit's
 * lockfile resolves, so CI builds exactly what you reviewed.
 *
 * Usage:
 *   node scripts/pin-scratch-gui.mjs [--gui <scratch-gui dir>] [--sha <sha>] [--dry-run]
 *
 * --gui defaults to ./scratch-gui, then ../scratch-gui, whichever exists.
 * --sha defaults to the checkout's HEAD.
 */

import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

import {engineShaInLock} from './verify-engine.mjs';
import {normalisePath} from './lib/win-path.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCK_PATH = path.join(ROOT, 'engine-lock.json');

const fail = message => {
    console.error(`\n[pin-scratch-gui] ${message}\n`);
    process.exit(1);
};

const git = (args, cwd) => spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: {...process.env, GIT_TERMINAL_PROMPT: '0'},
    maxBuffer: 64 * 1024 * 1024
});

const gitOk = (args, cwd) => {
    const result = git(args, cwd);
    return result.status === 0 ? (result.stdout || '').trim() : null;
};

const parseArgs = () => {
    const argv = process.argv.slice(2);
    const opts = {gui: null, sha: null, dryRun: false};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--gui') opts.gui = argv[++i];
        else if (argv[i] === '--sha') opts.sha = argv[++i];
        else if (argv[i] === '--dry-run') opts.dryRun = true;
        else if (argv[i] === '--help' || argv[i] === '-h') {
            console.log('Usage: node scripts/pin-scratch-gui.mjs [--gui <dir>] [--sha <sha>] [--dry-run]');
            process.exit(0);
        } else fail(`unknown argument: ${argv[i]}`);
    }
    return opts;
};

const resolveGuiDir = explicit => {
    const candidates = explicit
        ? [path.resolve(process.cwd(), normalisePath(explicit))]
        : [path.join(ROOT, 'scratch-gui'), path.resolve(ROOT, '..', 'scratch-gui')];
    for (const candidate of candidates) {
        if (fs.existsSync(path.join(candidate, '.git'))) return candidate;
    }
    fail(
        'no scratch-gui checkout found. Pass one explicitly:\n\n' +
        '    node scripts/pin-scratch-gui.mjs --gui D:/Bilup/scratch-gui'
    );
};

const main = () => {
    const opts = parseArgs();
    const guiDir = resolveGuiDir(opts.gui);
    const lock = JSON.parse(fs.readFileSync(LOCK_PATH, 'utf8'));
    const repo = lock.scratchGui.repository;

    const sha = opts.sha || gitOk(['rev-parse', 'HEAD'], guiDir);
    if (!sha || !/^[0-9a-f]{40}$/.test(sha)) {
        fail(`could not resolve a commit in ${guiDir}`);
    }
    const shortSha = sha.slice(0, 12);

    console.log(`[pin-scratch-gui] checkout : ${guiDir}`);
    console.log(`[pin-scratch-gui] commit   : ${sha}`);
    console.log(`[pin-scratch-gui] subject  : ${gitOk(['log', '-1', '--format=%cI %s', sha], guiDir) || '(unknown)'}`);

    // A pin that the remote cannot serve makes CI fail on the very first step,
    // so say so here rather than after a 20 minute pipeline.
    const remoteDevelop = gitOk(['ls-remote', repo, 'refs/heads/develop'], guiDir);
    if (remoteDevelop === null) {
        console.log('[pin-scratch-gui] warning: could not reach the remote to confirm the commit is pushed');
    } else {
        const remoteSha = remoteDevelop.split(/\s+/)[0];
        if (remoteSha === sha) {
            console.log(`[pin-scratch-gui] remote develop is at this commit: confirmed pushed`);
        } else {
            console.log('');
            console.log(`[pin-scratch-gui] warning: remote develop is at ${remoteSha.slice(0, 12)}, not ${shortSha}.`);
            console.log('    CI fetches the pinned SHA, so it will fail until this commit is pushed:');
            console.log(`        git -C ${guiDir} push origin develop`);
            console.log('');
        }
    }

    const lockText = sha === gitOk(['rev-parse', 'HEAD'], guiDir)
        ? fs.readFileSync(path.join(guiDir, 'pnpm-lock.yaml'), 'utf8')
        : gitOk(['show', `${sha}:pnpm-lock.yaml`], guiDir);
    if (!lockText) fail(`could not read pnpm-lock.yaml at ${shortSha}`);

    const engineShas = {};
    for (const engine of Object.keys(lock.requiredEngines)) {
        const found = engineShaInLock(lockText, engine);
        if (found.length !== 1) {
            fail(
                `pnpm-lock.yaml at ${shortSha} resolves ${engine} to ` +
                `${found.length} SHAs (${found.join(', ') || 'none'}); refusing to guess`
            );
        }
        engineShas[engine] = found[0];
    }

    const changes = [];
    if (lock.scratchGui.sha !== sha) {
        changes.push(['scratchGui.sha', lock.scratchGui.sha, sha]);
    }
    for (const [engine, engineSha] of Object.entries(engineShas)) {
        if (lock.requiredEngines[engine].sha !== engineSha) {
            changes.push([`requiredEngines.${engine}.sha`, lock.requiredEngines[engine].sha, engineSha]);
        }
    }

    if (changes.length === 0) {
        console.log('\n[pin-scratch-gui] engine-lock.json already matches; nothing to do\n');
        return;
    }

    console.log('\n[pin-scratch-gui] changes:');
    for (const [field, before, after] of changes) {
        console.log(`  ${field}`);
        console.log(`    ${String(before).slice(0, 12)} -> ${String(after).slice(0, 12)}`);
    }
    console.log('');

    if (opts.dryRun) {
        console.log('[pin-scratch-gui] --dry-run: engine-lock.json not written\n');
        return;
    }

    lock.scratchGui.sha = sha;
    lock.pinnedAt = new Date().toISOString();
    for (const [engine, engineSha] of Object.entries(engineShas)) {
        lock.requiredEngines[engine].sha = engineSha;
    }
    fs.writeFileSync(LOCK_PATH, `${JSON.stringify(lock, null, 2)}\n`, 'utf8');

    console.log(`[pin-scratch-gui] wrote ${path.relative(ROOT, LOCK_PATH)}`);
    console.log('[pin-scratch-gui] if an engine SHA moved, review the "why"/"contains" text next');
    console.log('    to it so the note still describes what is actually pinned, then:');
    console.log('');
    console.log('        node scripts/verify-engine.mjs lock');
    console.log('');
};

main();
