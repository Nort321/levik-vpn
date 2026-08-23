export default function DashboardLoading() {
  return (
    <div aria-busy="true" aria-label="Загружаем личный кабинет" className="dashboard-loading">
      <div className="skeleton skeleton--title" />
      <div className="dashboard-stats">
        <div className="skeleton skeleton--stat" />
        <div className="skeleton skeleton--stat" />
        <div className="skeleton skeleton--stat" />
      </div>
      <div className="skeleton skeleton--panel" />
      <div className="dashboard-columns">
        <div className="skeleton skeleton--panel-small" />
        <div className="skeleton skeleton--panel-small" />
      </div>
      <span className="sr-only">Загрузка…</span>
    </div>
  );
}
