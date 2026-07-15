import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import {
  DefaultPackageManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { createJiti } from "jiti/static";

export type AetherJsonObject = Record<string, unknown>;
export type AetherView = AetherJsonObject | AetherView[] | string | null | undefined;
export type AetherRenderContext = AetherJsonObject & {
  extension: {
    id: string;
    name: string;
    path: string;
  };
  storage: AetherJsonObject;
};

export interface AetherExtensionTransport {
  requestHost(method: string, args: AetherJsonObject): Promise<AetherJsonObject>;
  invalidate(version: number): void;
  notify(message: string, level: "info" | "warning" | "error"): void;
}

export interface AetherSurfaceDefinition {
  id?: string;
  order?: number;
  render?: AetherView | ((context: AetherRenderContext) => AetherView | Promise<AetherView>);
  tree?: AetherView;
}

export interface AetherPageDefinition extends AetherSurfaceDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
}

export type AetherComponentMode = "before" | "after" | "replace" | "wrap" | "hide";

export interface AetherComponentDefinition extends AetherSurfaceDefinition {
  mode?: AetherComponentMode;
}

export interface AetherActionContext extends AetherRenderContext {
  action: string;
}

export interface AetherEventContext extends AetherRenderContext {
  event: string;
}

export interface AetherExtensionAPI {
  readonly apiVersion: 2;
  readonly extension: {
    id: string;
    name: string;
    path: string;
  };
  readonly ui: typeof ui;
  readonly host: {
    invoke(method: string, args?: AetherJsonObject): Promise<AetherJsonObject>;
  };
  readonly services: {
    list(): Promise<AetherJsonObject>;
    describe(service: string): Promise<AetherJsonObject>;
    invoke(
      service: string,
      method: string,
      args?: AetherJsonObject,
    ): Promise<AetherJsonObject>;
  };
  readonly state: {
    get(path?: string): Promise<AetherJsonObject>;
    patch(path: string, value: unknown): Promise<AetherJsonObject>;
    transaction(
      operations: Array<{
        op?: "set" | "remove";
        path: string;
        value?: unknown;
      }>,
    ): Promise<AetherJsonObject>;
  };
  readonly storage: {
    get<T = unknown>(key: string, fallback?: T): T;
    set(key: string, value: unknown): void;
    delete(key: string): void;
    clear(): void;
    snapshot(): AetherJsonObject;
  };
  registerSurface(
    slot: string,
    definition:
      | AetherSurfaceDefinition
      | AetherView
      | ((context: AetherRenderContext) => AetherView | Promise<AetherView>),
  ): () => void;
  registerComponent(
    target: string,
    definition:
      | AetherComponentDefinition
      | AetherView
      | ((context: AetherRenderContext) => AetherView | Promise<AetherView>),
  ): () => void;
  registerPage(definition: AetherPageDefinition): () => void;
  registerAction(
    id: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherActionContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  on(
    event: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  intercept(
    operation: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  invalidate(): void;
  notify(message: string, level?: "info" | "warning" | "error"): void;
}

type AetherExtensionFactory = (
  api: AetherExtensionAPI,
) => void | (() => void | Promise<void>) | Promise<void | (() => void | Promise<void>)>;

interface AetherExtensionDescriptor {
  id: string;
  name: string;
  path: string;
  explicit: boolean;
  packageSource?: string;
}

interface DiscoveredAetherEntry {
  path: string;
  name: string;
  explicit: boolean;
  packageSource?: string;
}

interface LoadedAetherExtension extends AetherExtensionDescriptor {
  cleanup?: () => void | Promise<void>;
}

interface RegisteredSurface {
  id: string;
  extension: LoadedAetherExtension;
  slot: string;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredComponent {
  id: string;
  extension: LoadedAetherExtension;
  target: string;
  mode: AetherComponentMode;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredPage {
  id: string;
  localId: string;
  extension: LoadedAetherExtension;
  title: string;
  subtitle: string;
  icon: string;
  order: number;
  render: AetherSurfaceDefinition["render"];
}

interface RegisteredAction {
  extension: LoadedAetherExtension;
  localId: string;
  handler: (
    payload: AetherJsonObject,
    context: AetherActionContext,
  ) => unknown | Promise<unknown>;
}

interface RegisteredEventHandler {
  extension: LoadedAetherExtension;
  handler: (
    payload: AetherJsonObject,
    context: AetherEventContext,
  ) => unknown | Promise<unknown>;
}

interface AetherExtensionError {
  path: string;
  extension_id?: string;
  phase: "load" | "render" | "action" | "event";
  error: string;
}

interface AetherRuntimeState {
  cwd: string;
  extensions: LoadedAetherExtension[];
  surfaces: Map<string, RegisteredSurface>;
  components: Map<string, RegisteredComponent>;
  pages: Map<string, RegisteredPage>;
  actions: Map<string, RegisteredAction>;
  events: Map<string, RegisteredEventHandler[]>;
  errors: AetherExtensionError[];
}

const AETHER_API_VERSION = 2;
const AETHER_AGENT_DIRECTORY = path.join(os.homedir(), ".pi", "agent");
const AETHER_EXTENSION_ROOT = path.join(os.homedir(), ".aether", "extensions");
const PI_EXTENSION_ROOT = path.join(AETHER_AGENT_DIRECTORY, "extensions");
const AETHER_STORAGE_FILE = path.join(os.homedir(), ".aether", "app-extension-state.json");
const EXTENSION_FILE_PATTERN = /\.(?:[cm]?[jt]s)$/i;
const INDEX_FILE_NAMES = [
  "index.ts",
  "index.js",
  "index.mts",
  "index.mjs",
  "index.cts",
  "index.cjs",
];

const emptyTransport: AetherExtensionTransport = {
  async requestHost() {
    throw new Error("The Aether Android host is not connected.");
  },
  invalidate() {},
  notify() {},
};

let transport: AetherExtensionTransport = emptyTransport;
let runtime: AetherRuntimeState = createEmptyRuntime(process.cwd());
let runtimeVersion = 0;
let latestHostContext: AetherJsonObject = {};
let persistedStorage = readPersistedStorage();

function createEmptyRuntime(cwd: string): AetherRuntimeState {
  return {
    cwd,
    extensions: [],
    surfaces: new Map(),
    components: new Map(),
    pages: new Map(),
    actions: new Map(),
    events: new Map(),
    errors: [],
  };
}

function asObject(value: unknown): AetherJsonObject {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as AetherJsonObject)
    : {};
}

function cloneJson<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readJsonFile(filePath: string): AetherJsonObject | undefined {
  try {
    return asObject(JSON.parse(fs.readFileSync(filePath, "utf8")));
  } catch {
    return undefined;
  }
}

function readPersistedStorage(): Record<string, AetherJsonObject> {
  return asObject(readJsonFile(AETHER_STORAGE_FILE)) as Record<string, AetherJsonObject>;
}

function isPathDisabled(
  candidatePath: string,
  disabledPaths: Set<string>,
): boolean {
  const candidate = path.resolve(candidatePath);
  return [...disabledPaths].some((disabledPath) => {
    const relative = path.relative(disabledPath, candidate);
    return relative === "" || (
      !relative.startsWith(`..${path.sep}`) &&
      relative !== ".." &&
      !path.isAbsolute(relative)
    );
  });
}

function writePersistedStorage(): void {
  fs.mkdirSync(path.dirname(AETHER_STORAGE_FILE), { recursive: true });
  const temporaryPath = `${AETHER_STORAGE_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(temporaryPath, JSON.stringify(persistedStorage, null, 2), "utf8");
  fs.renameSync(temporaryPath, AETHER_STORAGE_FILE);
}

function extensionStorage(extensionId: string): AetherJsonObject {
  const current = asObject(persistedStorage[extensionId]);
  persistedStorage[extensionId] = current;
  return current;
}

function bumpVersion(): void {
  runtimeVersion += 1;
  transport.invalidate(runtimeVersion);
}

function stableExtensionId(name: string, entryPath: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "extension";
  const hash = createHash("sha256").update(path.resolve(entryPath)).digest("hex").slice(0, 10);
  return `${slug}:${hash}`;
}

function manifestAetherEntries(
  directory: string,
  packageSource?: string,
): Array<{ path: string; name: string; explicit: true }> {
  const manifest = readJsonFile(path.join(directory, "package.json"));
  const aether = asObject(manifest?.aether);
  const configured = aether.extensions;
  if (!Array.isArray(configured)) return [];
  const packageName =
    (typeof manifest?.name === "string" && manifest.name.trim()) ||
    path.basename(directory);
  return configured
    .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
    .map((entry) => ({
      path: path.resolve(directory, entry),
      name: packageName,
      explicit: true as const,
      packageSource,
    }))
    .filter((entry) => fs.existsSync(entry.path));
}

function implicitAetherEntry(
  candidatePath: string,
): Array<{ path: string; name: string; explicit: false }> {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(candidatePath);
  } catch {
    return [];
  }
  if (stat.isFile()) {
    return EXTENSION_FILE_PATTERN.test(candidatePath)
      ? [{ path: path.resolve(candidatePath), name: path.basename(candidatePath, path.extname(candidatePath)), explicit: false }]
      : [];
  }
  if (!stat.isDirectory()) return [];
  const configured = manifestAetherEntries(candidatePath);
  if (configured.length > 0) return [];
  for (const fileName of INDEX_FILE_NAMES) {
    const entryPath = path.join(candidatePath, fileName);
    if (fs.existsSync(entryPath)) {
      return [{ path: entryPath, name: path.basename(candidatePath), explicit: false }];
    }
  }
  return [];
}

function entriesInRoot(
  root: string,
): DiscoveredAetherEntry[] {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) => !entry.name.startsWith(".aether-import-"))
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap<DiscoveredAetherEntry>((entry) => {
      const candidatePath = path.join(root, entry.name);
      if (entry.isDirectory()) {
        const configured = manifestAetherEntries(candidatePath);
        if (configured.length > 0) return configured;
      }
      return implicitAetherEntry(candidatePath);
    });
}

function createPackageManager(cwd: string): DefaultPackageManager {
  const settingsManager = SettingsManager.create(cwd, AETHER_AGENT_DIRECTORY, {
    projectTrusted: false,
  });
  return new DefaultPackageManager({
    cwd,
    agentDir: AETHER_AGENT_DIRECTORY,
    settingsManager,
  });
}

async function packageAetherEntries(
  cwd: string,
): Promise<Array<{ path: string; name: string; explicit: true; packageSource: string }>> {
  const packageManager = createPackageManager(cwd);
  await packageManager.resolve();
  return packageManager
    .listConfiguredPackages()
    .filter((configuredPackage) => configuredPackage.scope === "user")
    .flatMap((configuredPackage) => {
      if (!configuredPackage.installedPath) return [];
      return manifestAetherEntries(
        configuredPackage.installedPath,
        configuredPackage.source,
      ).map((entry) => ({
        ...entry,
        packageSource: configuredPackage.source,
      }));
    });
}

async function discoverAetherExtensionEntries(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<AetherExtensionDescriptor[]> {
  const disabledExtensionPaths = new Set(
    (loadOptions.disabledExtensionPaths ?? []).map((entry) => path.resolve(entry)),
  );
  const disabledPackageSources = new Set(loadOptions.disabledPackageSources ?? []);
  const entries = [
    ...entriesInRoot(AETHER_EXTENSION_ROOT),
    ...entriesInRoot(PI_EXTENSION_ROOT),
    ...(await packageAetherEntries(cwd)),
  ];
  const seen = new Set<string>();
  return entries.flatMap((entry) => {
    const resolvedPath = path.resolve(entry.path);
    if (
      isPathDisabled(resolvedPath, disabledExtensionPaths) ||
      (entry.packageSource && disabledPackageSources.has(entry.packageSource))
    ) {
      return [];
    }
    if (seen.has(resolvedPath)) return [];
    seen.add(resolvedPath);
    return [{
      id: stableExtensionId(entry.name, resolvedPath),
      name: entry.name,
      path: resolvedPath,
      explicit: entry.explicit,
      packageSource: entry.packageSource,
    }];
  });
}

function normalizeSurfaceDefinition(
  definition:
    | AetherSurfaceDefinition
    | AetherView
    | ((context: AetherRenderContext) => AetherView | Promise<AetherView>),
): AetherSurfaceDefinition {
  if (typeof definition === "function") return { render: definition };
  if (
    definition &&
    typeof definition === "object" &&
    !Array.isArray(definition) &&
    (
      Object.prototype.hasOwnProperty.call(definition, "render") ||
      Object.prototype.hasOwnProperty.call(definition, "tree") ||
      Object.prototype.hasOwnProperty.call(definition, "order") ||
      Object.prototype.hasOwnProperty.call(definition, "id") ||
      Object.prototype.hasOwnProperty.call(definition, "mode")
    )
  ) {
    return definition as AetherSurfaceDefinition;
  }
  return { tree: definition as AetherView };
}

function scopedId(extensionId: string, localId: string): string {
  return `${extensionId}:${localId.trim()}`;
}

function renderValue(definition: AetherSurfaceDefinition): AetherSurfaceDefinition["render"] {
  return definition.render ?? definition.tree;
}

function createApiEventRegistration(
  runtimeState: AetherRuntimeState,
  extension: LoadedAetherExtension,
  eventName: string,
  handler: RegisteredEventHandler["handler"],
): () => void {
  const registration = { extension, handler };
  const handlers = runtimeState.events.get(eventName) ?? [];
  handlers.push(registration);
  runtimeState.events.set(eventName, handlers);
  return () => {
    const current = runtimeState.events.get(eventName) ?? handlers;
    const updated = current.filter((entry) => entry !== registration);
    if (updated.length > 0) runtimeState.events.set(eventName, updated);
    else runtimeState.events.delete(eventName);
  };
}

function createRenderContext(extension: LoadedAetherExtension): AetherRenderContext {
  return {
    ...cloneJson(latestHostContext),
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    storage: cloneJson(extensionStorage(extension.id)),
  };
}

function createApi(extension: LoadedAetherExtension): AetherExtensionAPI {
  return {
    apiVersion: AETHER_API_VERSION,
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    ui,
    host: {
      invoke(method, args = {}) {
        return transport.requestHost(method, cloneJson(args));
      },
    },
    services: {
      list() {
        return transport.requestHost("kernel.listServices", {});
      },
      describe(service) {
        return transport.requestHost("kernel.describeService", { service });
      },
      invoke(service, method, args = {}) {
        return transport.requestHost("service.invoke", {
          service,
          method,
          args: cloneJson(args),
        });
      },
    },
    state: {
      get(path = "") {
        return transport.requestHost("state.get", { path });
      },
      patch(path, value) {
        return transport.requestHost("state.transaction", {
          operations: [{ op: "set", path, value: cloneJson(value) }],
        });
      },
      transaction(operations) {
        return transport.requestHost("state.transaction", {
          operations: cloneJson(operations),
        });
      },
    },
    storage: {
      get<T>(key: string, fallback?: T): T {
        const storage = extensionStorage(extension.id);
        return (Object.prototype.hasOwnProperty.call(storage, key)
          ? cloneJson(storage[key])
          : fallback) as T;
      },
      set(key: string, value: unknown) {
        extensionStorage(extension.id)[key] = cloneJson(value);
        writePersistedStorage();
        bumpVersion();
      },
      delete(key: string) {
        delete extensionStorage(extension.id)[key];
        writePersistedStorage();
        bumpVersion();
      },
      clear() {
        persistedStorage[extension.id] = {};
        writePersistedStorage();
        bumpVersion();
      },
      snapshot() {
        return cloneJson(extensionStorage(extension.id));
      },
    },
    registerSurface(slot, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition);
      const localId = definition.id?.trim() || `${slot}-${runtime.surfaces.size + 1}`;
      const id = scopedId(extension.id, localId);
      runtime.surfaces.set(id, {
        id,
        extension,
        slot: slot.trim(),
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: renderValue(definition),
      });
      bumpVersion();
      return () => {
        if (runtime.surfaces.delete(id)) bumpVersion();
      };
    },
    registerComponent(target, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition) as AetherComponentDefinition;
      const normalizedTarget = target.trim();
      if (!normalizedTarget) {
        throw new Error("Aether extension components require a target.");
      }
      const localId = definition.id?.trim() ||
        `${normalizedTarget}-${runtime.components.size + 1}`;
      const id = scopedId(extension.id, localId);
      const requestedMode = definition.mode?.trim().toLowerCase();
      const mode: AetherComponentMode = (
        requestedMode === "before" ||
        requestedMode === "after" ||
        requestedMode === "replace" ||
        requestedMode === "wrap" ||
        requestedMode === "hide"
      ) ? requestedMode : "wrap";
      runtime.components.set(id, {
        id,
        extension,
        target: normalizedTarget,
        mode,
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: mode === "hide" ? undefined : renderValue(definition),
      });
      bumpVersion();
      return () => {
        if (runtime.components.delete(id)) bumpVersion();
      };
    },
    registerPage(definition) {
      const localId = definition.id.trim();
      if (!localId) throw new Error("Aether extension pages require an id.");
      if (!definition.title.trim()) throw new Error("Aether extension pages require a title.");
      const id = scopedId(extension.id, localId);
      runtime.pages.set(id, {
        id,
        localId,
        extension,
        title: definition.title,
        subtitle: definition.subtitle ?? "",
        icon: definition.icon ?? "extension",
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: renderValue(definition),
      });
      bumpVersion();
      return () => {
        if (runtime.pages.delete(id)) bumpVersion();
      };
    },
    registerAction(id, handler) {
      const localId = id.trim();
      if (!localId) throw new Error("Aether extension actions require an id.");
      const idScoped = scopedId(extension.id, localId);
      runtime.actions.set(idScoped, { extension, localId, handler });
      return () => {
        runtime.actions.delete(idScoped);
      };
    },
    on(event, handler) {
      const eventName = event.trim();
      if (!eventName) throw new Error("Aether extension event handlers require an event name.");
      return createApiEventRegistration(runtime, extension, eventName, handler);
    },
    intercept(operation, handler) {
      const operationName = operation.trim();
      if (!operationName) {
        throw new Error("Aether operation interceptors require an operation name.");
      }
      return createApiEventRegistration(
        runtime,
        extension,
        `operation:${operationName}`,
        handler,
      );
    },
    invalidate: bumpVersion,
    notify(message, level = "info") {
      transport.notify(message, level);
    },
  };
}

async function loadFactory(
  descriptor: AetherExtensionDescriptor,
): Promise<AetherExtensionFactory | undefined> {
  const jiti = createJiti(import.meta.url, {
    moduleCache: false,
    fsCache: false,
    tryNative: false,
    virtualModules: {
      "@aether/extension-api": aetherExtensionApiModule,
      "@aether/android-extension": aetherExtensionApiModule,
    },
  });
  const imported = await jiti.import<Record<string, unknown>>(descriptor.path);
  const namedFactory = imported.activateAether ?? imported.aether;
  const factory = typeof namedFactory === "function"
    ? namedFactory
    : descriptor.explicit && typeof imported.default === "function"
      ? imported.default
      : undefined;
  return factory as AetherExtensionFactory | undefined;
}

async function cleanupRuntime(previous: AetherRuntimeState): Promise<void> {
  for (const extension of [...previous.extensions].reverse()) {
    if (!extension.cleanup) continue;
    try {
      await extension.cleanup();
    } catch {
      // Reload should continue even when an extension's teardown fails.
    }
  }
}

export function configureAetherExtensionTransport(nextTransport: AetherExtensionTransport): void {
  transport = nextTransport;
}

export async function loadAetherAppExtensions(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<void> {
  const previous = runtime;
  runtime = createEmptyRuntime(cwd);
  await cleanupRuntime(previous);
  const descriptors = await discoverAetherExtensionEntries(cwd, loadOptions);
  for (const descriptor of descriptors) {
    const extension: LoadedAetherExtension = { ...descriptor };
    try {
      const factory = await loadFactory(descriptor);
      if (!factory) continue;
      runtime.extensions.push(extension);
      const cleanup = await factory(createApi(extension));
      if (typeof cleanup === "function") extension.cleanup = cleanup;
    } catch (error) {
      runtime.errors.push({
        path: descriptor.path,
        extension_id: descriptor.id,
        phase: "load",
        error: errorMessage(error),
      });
    }
  }
  bumpVersion();
}

async function renderRegisteredView(
  extension: LoadedAetherExtension,
  render: AetherSurfaceDefinition["render"],
  phasePath: string,
): Promise<AetherView> {
  try {
    const value = typeof render === "function"
      ? await render(createRenderContext(extension))
      : render;
    return cloneJson(value);
  } catch (error) {
    runtime.errors.push({
      path: phasePath,
      extension_id: extension.id,
      phase: "render",
      error: errorMessage(error),
    });
    return {
      type: "card",
      tone: "error",
      children: [
        {
          type: "text",
          text: `Extension render failed: ${errorMessage(error)}`,
          color: "error",
        },
      ],
    };
  }
}

export async function aetherAppExtensionSnapshot(
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const surfaces = [];
  for (const surface of [...runtime.surfaces.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    surfaces.push({
      id: surface.id,
      extension_id: surface.extension.id,
      extension_name: surface.extension.name,
      slot: surface.slot,
      order: surface.order,
      tree: await renderRegisteredView(surface.extension, surface.render, surface.id),
    });
  }
  const pages = [];
  for (const page of [...runtime.pages.values()].sort((left, right) =>
    left.order - right.order || left.title.localeCompare(right.title)
  )) {
    pages.push({
      id: page.id,
      local_id: page.localId,
      extension_id: page.extension.id,
      extension_name: page.extension.name,
      title: page.title,
      subtitle: page.subtitle,
      icon: page.icon,
      order: page.order,
      tree: await renderRegisteredView(page.extension, page.render, page.id),
    });
  }
  const components = [];
  for (const component of [...runtime.components.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    components.push({
      id: component.id,
      extension_id: component.extension.id,
      extension_name: component.extension.name,
      target: component.target,
      mode: component.mode,
      order: component.order,
      tree: component.mode === "hide"
        ? null
        : await renderRegisteredView(component.extension, component.render, component.id),
    });
  }
  return {
    api_version: AETHER_API_VERSION,
    version: runtimeVersion,
    extensions: runtime.extensions.map((extension) => ({
      id: extension.id,
      name: extension.name,
      path: extension.path,
    })),
    surfaces,
    components,
    pages,
    event_names: [...runtime.events.keys()].sort(),
    errors: runtime.errors.slice(-100),
  };
}

export async function invokeAetherAppExtensionAction(
  extensionId: string,
  actionId: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const id = actionId.includes(":") ? actionId : scopedId(extensionId, actionId);
  const action = runtime.actions.get(id);
  if (!action) throw new Error(`Unknown Aether extension action: ${actionId}`);
  try {
    const result = await action.handler(
      cloneJson(payload),
      {
        ...createRenderContext(action.extension),
        action: action.localId,
      },
    );
    bumpVersion();
    return {
      invoked: true,
      action: action.localId,
      result: cloneJson(result),
    };
  } catch (error) {
    runtime.errors.push({
      path: action.extension.path,
      extension_id: action.extension.id,
      phase: "action",
      error: errorMessage(error),
    });
    bumpVersion();
    throw error;
  }
}

export async function dispatchAetherAppExtensionEvent(
  eventName: string,
  payload: AetherJsonObject,
  hostContext: AetherJsonObject = {},
): Promise<AetherJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const handlers = runtime.events.get(eventName) ?? [];
  let chainedPayload = cloneJson(payload);
  let cancelled = false;
  let reason = "";
  const results: unknown[] = [];
  for (const registration of handlers) {
    try {
      const rawResult = await registration.handler(
        cloneJson(chainedPayload),
        {
          ...createRenderContext(registration.extension),
          event: eventName,
        },
      );
      results.push(cloneJson(rawResult));
      const result = asObject(rawResult);
      if (result.cancel === true || result.cancelled === true) {
        cancelled = true;
        reason = typeof result.reason === "string" ? result.reason : reason;
      }
      const explicitPayload = asObject(result.payload);
      const patch = Object.keys(explicitPayload).length > 0
        ? explicitPayload
        : Object.fromEntries(
          Object.entries(result).filter(([key]) =>
            !["cancel", "cancelled", "reason", "result"].includes(key)
          ),
        );
      if (Object.keys(patch).length > 0) {
        chainedPayload = { ...chainedPayload, ...cloneJson(patch) };
      }
      if (cancelled) break;
    } catch (error) {
      runtime.errors.push({
        path: registration.extension.path,
        extension_id: registration.extension.id,
        phase: "event",
        error: errorMessage(error),
      });
    }
  }
  if (handlers.length > 0) bumpVersion();
  return {
    event: eventName,
    handled: handlers.length > 0,
    cancelled,
    reason,
    payload: chainedPayload,
    results,
  };
}

export function aetherAppExtensionCountForManifest(
  manifest: AetherJsonObject | undefined,
): number {
  const extensions = asObject(manifest?.aether).extensions;
  return Array.isArray(extensions)
    ? extensions.filter((entry) => typeof entry === "string" && entry.trim().length > 0).length
    : 0;
}

function node(type: string, properties: AetherJsonObject = {}): AetherJsonObject {
  return { type, ...properties };
}

export const ui = {
  node,
  text(text: string, properties: AetherJsonObject = {}) {
    return node("text", { text, ...properties });
  },
  code(text: string, properties: AetherJsonObject = {}) {
    return node("code", { text, ...properties });
  },
  column(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("column", { children, ...properties });
  },
  row(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("row", { children, ...properties });
  },
  box(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("box", { children, ...properties });
  },
  card(children: AetherView[], properties: AetherJsonObject = {}) {
    return node("card", { children, ...properties });
  },
  button(label: string, action: string, properties: AetherJsonObject = {}) {
    return node("button", { label, action, ...properties });
  },
  iconButton(icon: string, action: string, properties: AetherJsonObject = {}) {
    return node("iconButton", { icon, action, ...properties });
  },
  switch(label: string, checked: boolean, action: string, properties: AetherJsonObject = {}) {
    return node("switch", { label, checked, action, ...properties });
  },
  input(value: string, action: string, properties: AetherJsonObject = {}) {
    return node("input", { value, action, ...properties });
  },
  spacer(size = 8, properties: AetherJsonObject = {}) {
    return node("spacer", { size, ...properties });
  },
  progress(value?: number, properties: AetherJsonObject = {}) {
    return node("progress", { value, ...properties });
  },
  web(properties: AetherJsonObject) {
    return node("web", properties);
  },
  core(properties: AetherJsonObject = {}) {
    return node("core", properties);
  },
} as const;

export function defineAetherExtension<T extends AetherExtensionFactory>(factory: T): T {
  return factory;
}

export const aetherExtensionApiModule = {
  defineAetherExtension,
  ui,
};
