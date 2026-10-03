/**
 * Accept the path forms a Windows developer actually types.
 *
 * Git Bash hands over MSYS paths like /d/Bilup/scratch-gui. Node on Windows
 * treats that as rooted at the current drive and resolves it to D:\d\Bilup\...,
 * so a --gui / --dir argument that looks right silently points at nothing.
 * Rewriting the leading drive segment keeps both forms working.
 *
 * Only ever applied on Windows: on Linux a path like /home/runner/... starts
 * with a drive-shaped first segment too, and mangling it there would break CI.
 *
 * @param {string} value a path from the command line
 * @returns {string} the same path in a form path.resolve understands
 */
export const normalisePath = value => {
    if (typeof value !== 'string' || process.platform !== 'win32') return value;
    const match = /^\/([A-Za-z])\/(.*)$/.exec(value);
    return match ? `${match[1].toUpperCase()}:/${match[2]}` : value;
};
