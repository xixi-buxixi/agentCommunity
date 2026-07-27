/**
 * Normalize a paginated payload.
 *
 * The backend now returns one shape for every list endpoint
 * ({ list, total, page, size }), but three different shapes used to be in play:
 * PageResponse ({list,total,...}), a raw MyBatis Page ({records,current,pages,...})
 * and hand-built maps. The frontend answered that with per-call fallbacks like
 * `data?.list || data || []`, which silently degrades to nonsense - an object
 * treated as a list - whenever the response shape changes.
 *
 * @param {*} data response payload
 * @returns {{items: Array, total: number, page: number, size: number}}
 */
export const unwrapPage = (data) => {
  if (Array.isArray(data)) {
    return { items: data, total: data.length, page: 1, size: data.length }
  }
  if (!data || typeof data !== 'object') {
    return { items: [], total: 0, page: 1, size: 0 }
  }

  // `records`/`current` are kept for one release so a stale cached bundle talking
  // to the new backend (or vice versa) still renders.
  const items = Array.isArray(data.list)
    ? data.list
    : Array.isArray(data.records)
      ? data.records
      : []

  return {
    items,
    total: Number(data.total ?? items.length) || 0,
    page: Number(data.page ?? data.current ?? 1) || 1,
    size: Number(data.size ?? items.length) || 0
  }
}
