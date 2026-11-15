// In-memory stand-in for the platform DB client. Local development only.
// Mirrors the subset of the Supabase-style builder used by functions/routes.mjs.

const clone = (value) => (value === undefined ? value : structuredClone(value));

function compare(left, right) {
  if (typeof left === 'number' && typeof right === 'number') return left - right;
  return String(left ?? '').localeCompare(String(right ?? ''));
}

class Query {
  constructor(store, table) {
    this.store = store;
    this.table = table;
    this.action = 'select';
    this.filters = [];
    this.orders = [];
    this.limitCount = null;
    this.payload = null;
    this.columns = '*';
    this.singleMode = null;
    this.hasProjection = false;
  }

  select(columns = '*') {
    if (this.action === 'select') this.columns = columns;
    else this.hasProjection = true;
    return this;
  }

  insert(payload) {
    this.action = 'insert';
    this.payload = payload;
    return this;
  }

  update(payload) {
    this.action = 'update';
    this.payload = payload;
    return this;
  }

  delete() {
    this.action = 'delete';
    return this;
  }

  eq(column, value) {
    this.filters.push((row) => row[column] === value);
    return this;
  }

  in(column, values) {
    const set = new Set(values);
    this.filters.push((row) => set.has(row[column]));
    return this;
  }

  gte(column, value) {
    this.filters.push((row) => compare(row[column], value) >= 0);
    return this;
  }

  lte(column, value) {
    this.filters.push((row) => compare(row[column], value) <= 0);
    return this;
  }

  is(column, value) {
    this.filters.push((row) => (value === null ? row[column] === null || row[column] === undefined : row[column] === value));
    return this;
  }

  order(column, { ascending = true } = {}) {
    this.orders.push({ column, ascending });
    return this;
  }

  limit(count) {
    this.limitCount = count;
    return this;
  }

  maybeSingle() {
    this.singleMode = 'maybe';
    return this;
  }

  single() {
    this.singleMode = 'one';
    return this;
  }

  then(resolve, reject) {
    return Promise.resolve()
      .then(() => this.run())
      .then(resolve, reject);
  }

  rows() {
    return this.store.table(this.table);
  }

  matching() {
    let result = this.rows().filter((row) => this.filters.every((filter) => filter(row)));
    for (const { column, ascending } of [...this.orders].reverse()) {
      result = [...result].sort((a, b) => (ascending ? 1 : -1) * compare(a[column], b[column]));
    }
    return result;
  }

  project(row) {
    if (!row) return row;
    if (this.columns === '*') return clone(row);
    const keys = this.columns.split(',').map((key) => key.trim().replace(/:.*$/, ''));
    return Object.fromEntries(keys.map((key) => [key, clone(row[key])]));
  }

  async run() {
    const table = this.rows();
    let data = null;

    if (this.action === 'insert') {
      const incoming = Array.isArray(this.payload) ? this.payload : [this.payload];
      const created = incoming.map((raw) => {
        const row = { ...raw };
        if (!row.id) row.id = crypto.randomUUID();
        return row;
      });
      table.push(...created.map((row) => clone(row)));
      data = this.hasProjection ? created.map((row) => this.project(row)) : null;
    } else if (this.action === 'update') {
      const targets = this.matching();
      for (const target of targets) Object.assign(target, this.payload);
      data = this.hasProjection ? targets.map((row) => this.project(row)) : null;
    } else if (this.action === 'delete') {
      const targets = new Set(this.matching());
      this.store.set(this.table, table.filter((row) => !targets.has(row)));
      data = this.hasProjection ? [...targets].map((row) => this.project(row)) : null;
    } else {
      let matches = this.matching();
      if (this.limitCount != null) matches = matches.slice(0, this.limitCount);
      data = matches.map((row) => this.project(row));
    }

    if (this.singleMode === 'maybe') {
      if (data && data.length > 1) return { data: null, error: { code: 'multiple_rows', status: 406 } };
      data = data && data.length ? data[0] : null;
    } else if (this.singleMode === 'one') {
      if (!data || data.length !== 1) return { data: null, error: { code: 'no_rows', status: 406 } };
      data = data[0];
    }

    return { data, error: null, count: Array.isArray(data) ? data.length : null };
  }
}

export function createFakeDatabase() {
  const tables = new Map();
  const store = {
    tables: Object.create(null),
    table(name) {
      const list = tables.get(name) || [];
      tables.set(name, list);
      return list;
    },
    set(name, list) {
      tables.set(name, list);
    },
    snapshot() {
      return Object.fromEntries([...tables].map(([name, list]) => [name, clone(list)]));
    },
    reset() {
      tables.clear();
    },
  };
  return {
    from(name) {
      return new Query(store, name);
    },
    store,
  };
}
