import {
  Award,
  Building2,
  BookOpen,
  ClipboardCheck,
  Download,
  Eye,
  EyeOff,
  FileDown,
  FileText,
  Flag,
  KeyRound,
  Languages,
  LayoutGrid,
  LogIn,
  LogOut,
  Mail,
  Plus,
  Search,
  ShieldCheck,
  Save,
  Table2,
  Timer,
  Trophy,
  Trash2,
  UploadCloud,
  UserPlus
} from 'lucide-react';
import { FormEvent, useEffect, useMemo, useState } from 'react';
import heroPigeon from './assets/hero-pigeon.png';
import { ApiError, api, clearStoredAuth, downloadAsset, isUnauthorized, openAsset, readStoredAuth, writeStoredAuth, type ReportFilters } from './lib/api';
import { copy, Language } from './lib/i18n';
import type { AuthResponse, ClubAdminDto, DashboardDto, DatasetDto, DocumentDto, FederationAdminDto, LabelDto, LeaderboardDto, LoftAdminDto, OrganisationTreeDto, UploadResponse } from './lib/types';

type View = 'results' | 'leaderboards' | 'upload' | 'organisations';
type AuthMode = 'login' | 'register';
type TableMode = 'table' | 'cards';
type PasswordDialogMode = 'change' | 'forgot' | 'reset';

const familyOptions = [
  { value: '', labelKey: 'allTypes' },
  { value: 'RACE_DETAIL', labelKey: 'raceReports' },
  { value: 'CLASSIFICATION', labelKey: 'classifications' },
  { value: 'DISTANCE_LOG', labelKey: 'distanceLogs' }
] as const;

const categoryOptions = [
  '',
  'HOK_PUNTE',
  'OPE_PUNTE',
  'LEDE_PUNTE',
  'JO_PUNTE',
  'SHORT_DISTANCE',
  'MIDDLE_DISTANCE',
  'LONG_DISTANCE'
] as const;

const defaultAuth = {
  email: '',
  password: '',
  displayName: '',
  federationId: '',
  clubId: '',
  loftId: ''
};

const defaultPasswordForm = {
  email: '',
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
  token: ''
};

const emptyReportFilters: ReportFilters = {
  query: '',
  family: '',
  category: '',
  dateFrom: '',
  dateTo: '',
  federationId: '',
  clubId: '',
  loftId: '',
  racePoint: ''
};

const defaultReportFilters: ReportFilters = {
  ...emptyReportFilters,
  family: 'RACE_DETAIL'
};

function isAdminUser(user: AuthResponse | null | undefined) {
  return user?.role === 'SYSTEM_ADMIN' || user?.role === 'FEDERATION_ADMIN' || user?.role === 'ADMIN';
}

function isSystemAdminUser(user: AuthResponse | null | undefined) {
  return user?.role === 'SYSTEM_ADMIN' || user?.role === 'ADMIN';
}

export default function App() {
  const [language, setLanguage] = useState<Language>(() => {
    const storedLanguage = localStorage.getItem('vlugboek.language');
    return storedLanguage === 'en' ? 'en' : 'af';
  });
  const t = copy[language];
  const [view, setView] = useState<View>('results');
  const [authMode, setAuthMode] = useState<AuthMode>('login');
  const [user, setUser] = useState<AuthResponse | null>(() => readStoredAuth());
  const [authForm, setAuthForm] = useState(defaultAuth);
  const [dashboard, setDashboard] = useState<DashboardDto | null>(null);
  const [documents, setDocuments] = useState<DocumentDto[]>([]);
  const [leaderboards, setLeaderboards] = useState<LeaderboardDto[]>([]);
  const [federations, setFederations] = useState<LabelDto[]>([]);
  const [clubs, setClubs] = useState<LabelDto[]>([]);
  const [lofts, setLofts] = useState<LabelDto[]>([]);
  const [filterClubs, setFilterClubs] = useState<LabelDto[]>([]);
  const [filterLofts, setFilterLofts] = useState<LabelDto[]>([]);
  const [organisationTree, setOrganisationTree] = useState<OrganisationTreeDto | null>(null);
  const [reportFilters, setReportFilters] = useState<ReportFilters>(defaultReportFilters);
  const [selected, setSelected] = useState<DocumentDto | null>(null);
  const [dataset, setDataset] = useState<DatasetDto | null>(null);
  const [tableMode, setTableMode] = useState<TableMode>('table');
  const [passwordDialog, setPasswordDialog] = useState<PasswordDialogMode | null>(null);
  const [passwordForm, setPasswordForm] = useState(defaultPasswordForm);
  const [datasetQuery, setDatasetQuery] = useState('');
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [pendingImport, setPendingImport] = useState<UploadResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadingBase, setLoadingBase] = useState(false);
  const [loadingReports, setLoadingReports] = useState(false);
  const [loadingDataset, setLoadingDataset] = useState(false);
  const [loadingOrganisations, setLoadingOrganisations] = useState(false);
  const [notice, setNotice] = useState<string>('');

  useEffect(() => {
    localStorage.setItem('vlugboek.language', language);
    document.documentElement.lang = language;
  }, [language]);

  useEffect(() => {
    if (user) {
      writeStoredAuth(user);
      setLanguage(user.language === 'en' ? 'en' : 'af');
    } else {
      clearStoredAuth();
    }
  }, [user]);

  useEffect(() => {
    void loadFederations();
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const resetToken = params.get('resetToken');
    const email = params.get('email') ?? '';
    if (resetToken) {
      setPasswordForm({ ...defaultPasswordForm, email, token: resetToken });
      setPasswordDialog('reset');
    }
  }, []);

  useEffect(() => {
    if (!user) {
      clearPrivateData();
      return;
    }
    void loadBase();
  }, [user?.token]);

  useEffect(() => {
    if (!isAdminUser(user) && (view === 'upload' || view === 'organisations')) {
      setView('results');
    }
  }, [user?.role, view]);

  useEffect(() => {
    if (isAdminUser(user) && view === 'organisations') {
      void loadOrganisations();
    }
  }, [user?.role, view]);

  useEffect(() => {
    if (view === 'results') {
      setTableMode('table');
    }
  }, [view]);

  useEffect(() => {
    if (!user) return;
    const timeout = window.setTimeout(() => {
      void loadReports();
    }, 180);
    return () => window.clearTimeout(timeout);
  }, [reportFilters, user?.token]);

  useEffect(() => {
    if (!authForm.federationId) {
      setClubs([]);
      return;
    }
    void api.clubs(Number(authForm.federationId)).then(setClubs).catch(showError);
  }, [authForm.federationId]);

  useEffect(() => {
    if (!authForm.clubId) {
      setLofts([]);
      return;
    }
    void api.lofts(Number(authForm.clubId)).then(setLofts).catch(showError);
  }, [authForm.clubId]);

  useEffect(() => {
    if (!reportFilters.federationId) {
      setFilterClubs([]);
      return;
    }
    void api.clubs(Number(reportFilters.federationId)).then(setFilterClubs).catch(showError);
  }, [reportFilters.federationId]);

  useEffect(() => {
    if (!reportFilters.clubId) {
      setFilterLofts([]);
      return;
    }
    void api.lofts(Number(reportFilters.clubId)).then(setFilterLofts).catch(showError);
  }, [reportFilters.clubId]);

  const racePoints = useMemo(() => {
    const values = new Set<string>();
    documents.forEach((document) => {
      if (document.racePoint) values.add(document.racePoint);
    });
    if (reportFilters.racePoint) values.add(reportFilters.racePoint);
    return Array.from(values).sort((left, right) => left.localeCompare(right));
  }, [documents, reportFilters.racePoint]);

  async function loadFederations() {
    try {
      setFederations(await api.federations());
    } catch (error) {
      showError(error);
    }
  }

  async function loadOrganisations() {
    if (!isAdminUser(user)) return;
    setLoadingOrganisations(true);
    try {
      setOrganisationTree(await api.organisationTree());
    } catch (error) {
      showError(error);
    } finally {
      setLoadingOrganisations(false);
    }
  }

  async function loadBase() {
    setLoadingBase(true);
    try {
      const [dashboardData, reportData, leaderboardData, federationData] = await Promise.all([
        api.dashboard(),
        api.reports(reportFilters),
        api.leaderboards(),
        api.federations()
      ]);
      setDashboard(dashboardData);
      setDocuments(reportData);
      setLeaderboards(leaderboardData);
      setFederations(federationData);
      if (reportData.length > 0) {
        await selectDocument(preferredDocument(reportData));
      } else {
        setSelected(null);
        setDataset(null);
      }
    } catch (error) {
      showError(error);
    } finally {
      setLoadingBase(false);
    }
  }

  function clearPrivateData() {
    setDashboard(null);
    setDocuments([]);
    setLeaderboards([]);
    setSelected(null);
    setDataset(null);
    setUploadFile(null);
    setPendingImport(null);
  }

  async function loadReports() {
    setLoadingReports(true);
    try {
      const reportData = await api.reports(reportFilters);
      setDocuments(reportData);
      if (reportData.length && (!selected || !reportData.some((document) => document.id === selected.id))) {
        await selectDocument(preferredDocument(reportData));
      }
      if (!reportData.length) {
        setSelected(null);
        setDataset(null);
      }
    } catch (error) {
      showError(error);
    } finally {
      setLoadingReports(false);
    }
  }

  async function selectDocument(document: DocumentDto) {
    setLoadingDataset(true);
    try {
      setTableMode('table');
      setSelected(document);
      setDataset(null);
      setDatasetQuery('');
      setDataset(await api.report(document.id));
    } catch (error) {
      showError(error);
    } finally {
      setLoadingDataset(false);
    }
  }

  function preferredDocument(reportData: DocumentDto[]) {
    return reportData.find((document) => document.reportFamily === 'RACE_DETAIL') ?? reportData[0];
  }

  async function handleAuth(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const payload = {
        email: authForm.email,
        password: authForm.password,
        displayName: authForm.displayName,
        federationId: authForm.federationId ? Number(authForm.federationId) : undefined,
        clubId: authForm.clubId ? Number(authForm.clubId) : undefined,
        loftId: authForm.loftId ? Number(authForm.loftId) : undefined,
        language
      };
      setUser(authMode === 'login' ? await api.login(payload) : await api.register(payload));
      setNotice(authMode === 'login' ? 'Signed in' : 'Registered');
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  }

  function handleLogout() {
    clearStoredAuth();
    clearPrivateData();
    setUser(null);
    setAuthMode('login');
    setView('results');
    setNotice(t.signedOut);
  }

  async function changeLanguage(nextLanguage: Language) {
    setLanguage(nextLanguage);
    if (!user) return;

    try {
      setUser(await api.updateLanguage(nextLanguage));
    } catch (error) {
      showError(error);
    }
  }

  function openForgotPassword() {
    setPasswordForm({ ...defaultPasswordForm, email: authForm.email });
    setPasswordDialog('forgot');
  }

  function openChangePassword() {
    setPasswordForm({ ...defaultPasswordForm, email: user?.email ?? '' });
    setPasswordDialog('change');
  }

  function closePasswordDialog() {
    setPasswordDialog(null);
    setPasswordForm(defaultPasswordForm);
  }

  async function handlePasswordDialog(event: FormEvent) {
    event.preventDefault();
    if ((passwordDialog === 'change' || passwordDialog === 'reset') && passwordForm.newPassword !== passwordForm.confirmPassword) {
      setNotice(t.passwordsDoNotMatch);
      return;
    }

    setBusy(true);
    try {
      if (passwordDialog === 'change') {
        setUser(await api.changePassword({
          currentPassword: passwordForm.currentPassword,
          newPassword: passwordForm.newPassword
        }));
        setNotice(t.passwordChanged);
        closePasswordDialog();
        return;
      }

      if (passwordDialog === 'forgot') {
        await api.requestPasswordReset({ email: passwordForm.email, language });
        setNotice(t.resetLinkSent);
        closePasswordDialog();
        return;
      }

      if (passwordDialog === 'reset') {
        setUser(await api.confirmPasswordReset({
          email: passwordForm.email,
          token: passwordForm.token,
          password: passwordForm.newPassword,
          language
        }));
        window.history.replaceState({}, '', window.location.pathname);
        setNotice(t.passwordResetComplete);
        closePasswordDialog();
      }
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  }

  async function handleUpload() {
    if (!uploadFile) return;
    setBusy(true);
    try {
      const result = await api.upload(uploadFile);
      setNotice(result.message);
      setUploadFile(null);
      setPendingImport(result);
      setView('upload');
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirmImport() {
    if (!pendingImport) return;
    setBusy(true);
    try {
      const result = await api.confirmImport(pendingImport.document.id);
      setNotice(result.message);
      setPendingImport(null);
      await loadBase();
      await selectDocument(result.document);
      setView('results');
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  }

  async function emailDocument(document: DocumentDto) {
    try {
      const response = await api.emailDocument(document.id);
      setNotice(response.message);
    } catch (error) {
      showError(error);
    }
  }

  function updateReportFilters(next: Partial<ReportFilters>) {
    setReportFilters((current) => ({ ...current, ...next }));
  }

  function clearReportFilters() {
    setReportFilters(emptyReportFilters);
    setFilterClubs([]);
    setFilterLofts([]);
  }

  function showCurrentResults() {
    setTableMode('table');
    setReportFilters(defaultReportFilters);
    setFilterClubs([]);
    setFilterLofts([]);
    setView('results');
  }

  async function refreshOrganisations() {
    await Promise.all([loadFederations(), loadOrganisations()]);
  }

  function showError(error: unknown) {
    if (isUnauthorized(error)) {
      clearPrivateData();
      setUser(null);
      setNotice('Please sign in again');
      return;
    }
    if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
      setNotice(t.noAccess);
      return;
    }

    setNotice(error instanceof Error ? error.message : 'Something went wrong');
  }

  return (
    <main className="min-h-screen bg-ivory-100 text-midnight-950">
      <Hero
        t={t}
        language={language}
        setLanguage={changeLanguage}
        view={view}
        setView={setView}
        onCurrentResults={showCurrentResults}
        user={user}
        dashboard={dashboard}
        authMode={authMode}
        setAuthMode={setAuthMode}
        authForm={authForm}
        setAuthForm={setAuthForm}
        federations={federations}
        clubs={clubs}
        lofts={lofts}
        busy={busy}
        onAuth={handleAuth}
        onForgotPassword={openForgotPassword}
        onChangePassword={openChangePassword}
        onLogout={handleLogout}
      />

      {user ? (
        <section className="mx-auto grid w-full max-w-7xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[360px_1fr] lg:px-8">
          <aside className="space-y-4">
            <QuickStats dashboard={dashboard} t={t} />
            <ReportList
              t={t}
              documents={documents}
              selected={selected}
              filters={reportFilters}
              setFilters={updateReportFilters}
              clearFilters={clearReportFilters}
              showCurrent={showCurrentResults}
              racePoints={racePoints}
              federations={federations}
              clubs={filterClubs}
              lofts={filterLofts}
              isAdmin={isAdminUser(user)}
              loading={loadingReports || loadingBase}
              onSelect={selectDocument}
            />
          </aside>

          <div className="min-w-0">
            {notice && (
              <div className="mb-4 flex items-center justify-between rounded-lg border border-championship-500/40 bg-championship-400/15 px-4 py-3 text-sm text-midnight-900">
                <span>{notice}</span>
                <button className="font-semibold text-championship-600" onClick={() => setNotice('')}>
                  OK
                </button>
              </div>
            )}

            {view === 'results' && (
              <>
                {loadingDataset && <LoadingPanel t={t} message={t.loadingReport} />}
                {!loadingDataset && selected && dataset && (
                  <ResultsPanel
                    t={t}
                    document={selected}
                    dataset={dataset}
                    datasetQuery={datasetQuery}
                    setDatasetQuery={setDatasetQuery}
                    tableMode={tableMode}
                    setTableMode={setTableMode}
                    onEmail={emailDocument}
                    onError={showError}
                  />
                )}
                {!loadingDataset && !selected && <EmptyPanel icon={<FileText />} title={t.noReports} actionLabel={t.currentResults} onAction={showCurrentResults} />}
              </>
            )}

            {view === 'leaderboards' && <LeaderboardsPanel t={t} leaderboards={leaderboards} />}

            {view === 'upload' && isAdminUser(user) && (
              <UploadPanel
                t={t}
                busy={busy}
                uploadFile={uploadFile}
                setUploadFile={setUploadFile}
                pendingImport={pendingImport}
                onUpload={handleUpload}
                onConfirm={handleConfirmImport}
                documents={documents}
                onSelect={(document) => {
                  void selectDocument(document);
                  setView('results');
                }}
              />
            )}

            {view === 'organisations' && isAdminUser(user) && (
              <OrganisationAdminPanel
                t={t}
                user={user}
                tree={organisationTree}
                busy={busy || loadingOrganisations}
                onChanged={refreshOrganisations}
                onError={showError}
              />
            )}
          </div>
        </section>
      ) : (
        <section className="mx-auto w-full max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
          {notice && (
            <div className="mb-4 flex items-center justify-between rounded-lg border border-championship-500/40 bg-championship-400/15 px-4 py-3 text-sm text-midnight-900">
              <span>{notice}</span>
              <button className="font-semibold text-championship-600" onClick={() => setNotice('')}>
                OK
              </button>
            </div>
          )}
          <EmptyPanel icon={<ShieldCheck />} title={t.signInRequired} />
        </section>
      )}
      {passwordDialog && (
        <PasswordDialog
          t={t}
          mode={passwordDialog}
          form={passwordForm}
          setForm={setPasswordForm}
          busy={busy}
          onSubmit={handlePasswordDialog}
          onClose={closePasswordDialog}
        />
      )}
    </main>
  );
}

function Hero({
  t,
  language,
  setLanguage,
  view,
  setView,
  onCurrentResults,
  user,
  dashboard,
  authMode,
  setAuthMode,
  authForm,
  setAuthForm,
  federations,
  clubs,
  lofts,
  busy,
  onAuth,
  onForgotPassword,
  onChangePassword,
  onLogout
}: {
  t: Record<string, string>;
  language: Language;
  setLanguage: (language: Language) => void;
  view: View;
  setView: (view: View) => void;
  onCurrentResults: () => void;
  user: AuthResponse | null;
  dashboard: DashboardDto | null;
  authMode: AuthMode;
  setAuthMode: (mode: AuthMode) => void;
  authForm: typeof defaultAuth;
  setAuthForm: (form: typeof defaultAuth) => void;
  federations: LabelDto[];
  clubs: LabelDto[];
  lofts: LabelDto[];
  busy: boolean;
  onAuth: (event: FormEvent) => void;
  onForgotPassword: () => void;
  onChangePassword: () => void;
  onLogout: () => void;
}) {
  const [showPassword, setShowPassword] = useState(false);

  return (
    <section className="relative overflow-hidden bg-midnight-950 text-ivory-100">
      <img
        src={heroPigeon}
        alt=""
        className="hero-image-mask absolute inset-y-0 right-0 h-full w-full object-cover opacity-45 md:w-[64%] md:opacity-80"
      />
      <div className="absolute inset-0 bg-[linear-gradient(90deg,rgba(11,22,35,0.98)_0%,rgba(11,22,35,0.88)_44%,rgba(11,22,35,0.25)_100%)]" />

      <div className="relative mx-auto flex min-h-[680px] w-full max-w-7xl flex-col px-4 py-5 sm:px-6 lg:px-8">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-championship-500/50 bg-championship-500/15">
              <Flag className="h-5 w-5 text-championship-400" />
            </div>
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-championship-400">PWDF</p>
              <p className="font-display text-xl text-ivory-100">Vlugboek</p>
            </div>
          </div>
          <nav className="flex flex-wrap items-center gap-2">
            <NavButton active={view === 'results'} onClick={() => setView('results')} icon={<BookOpen />} label={t.results} />
            <NavButton active={view === 'leaderboards'} onClick={() => setView('leaderboards')} icon={<Trophy />} label={t.leaderboards} />
            {isAdminUser(user) && <NavButton active={view === 'upload'} onClick={() => setView('upload')} icon={<UploadCloud />} label={t.upload} />}
            {isAdminUser(user) && <NavButton active={view === 'organisations'} onClick={() => setView('organisations')} icon={<Building2 />} label={t.organisations} />}
            {user && (
              <button
                title={t.signOut}
                className="flex h-10 items-center gap-2 rounded-lg border border-ivory-100/20 px-3 text-sm font-semibold text-ivory-100 hover:border-championship-400 hover:text-championship-400"
                onClick={onLogout}
              >
                <LogOut className="h-4 w-4" />
                {t.signOut}
              </button>
            )}
            <button
              title={t.language}
              className="flex h-10 items-center gap-2 rounded-lg border border-ivory-100/20 px-3 text-sm text-ivory-100 hover:border-championship-400 hover:text-championship-400"
              onClick={() => setLanguage(language === 'en' ? 'af' : 'en')}
            >
              <Languages className="h-4 w-4" />
              {language.toUpperCase()}
            </button>
          </nav>
        </header>

        <div className="grid flex-1 items-center gap-8 py-10 lg:grid-cols-[minmax(0,1fr)_420px]">
          <div className="max-w-2xl">
            <p className="mb-4 inline-flex items-center gap-2 rounded-lg border border-championship-500/35 bg-midnight-900/70 px-3 py-2 text-sm text-championship-400">
              <ShieldCheck className="h-4 w-4" />
              {t.brandLine}
            </p>
            <h1 className="font-display text-6xl leading-none text-ivory-100 sm:text-7xl lg:text-8xl">{t.heroTitle}</h1>
            <p className="mt-5 max-w-xl text-lg leading-8 text-ivory-200">{t.heroCopy}</p>
            <div className="mt-8 flex flex-wrap gap-3">
              <button
                className="inline-flex h-12 items-center gap-2 rounded-lg bg-championship-500 px-5 font-semibold text-midnight-950 shadow-card hover:bg-championship-400"
                onClick={onCurrentResults}
              >
                <Eye className="h-5 w-5" />
                {t.viewResults}
              </button>
              {isAdminUser(user) && (
                <>
                  <button
                    className="inline-flex h-12 items-center gap-2 rounded-lg border border-championship-500/60 px-5 font-semibold text-championship-400 hover:bg-championship-500/10"
                    onClick={() => setView('upload')}
                  >
                    <UploadCloud className="h-5 w-5" />
                    {t.uploadPdf}
                  </button>
                  <button
                    className="inline-flex h-12 items-center gap-2 rounded-lg border border-ivory-100/25 px-5 font-semibold text-ivory-100 hover:border-championship-400 hover:text-championship-400"
                    onClick={() => setView('organisations')}
                  >
                    <Building2 className="h-5 w-5" />
                    {t.organisations}
                  </button>
                </>
              )}
            </div>
          </div>

          <div className="rounded-lg border border-ivory-100/15 bg-ivory-100/95 p-4 text-midnight-950 shadow-card backdrop-blur">
            {user ? (
              <div className="space-y-4">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm uppercase tracking-[0.18em] text-championship-600">{user.role}</p>
                    <h2 className="mt-1 font-display text-3xl">{user.displayName}</h2>
                  </div>
                  <Award className="h-9 w-9 text-championship-500" />
                </div>
                <div className="grid gap-2 text-sm text-slateInk">
                  <p>{user.email}</p>
                  <p>{[user.federation?.code, user.club?.name, user.loft?.name].filter(Boolean).join(' / ')}</p>
                </div>
                <div className="grid grid-cols-3 gap-2">
                  <MiniStat icon={<FileText />} label={t.documents} value={dashboard?.documentCount ?? 0} />
                  <MiniStat icon={<Timer />} label={t.races} value={dashboard?.raceCount ?? 0} />
                  <MiniStat icon={<Trophy />} label={t.snapshots} value={dashboard?.leaderboardCount ?? 0} />
                </div>
                <div className="grid gap-2 sm:grid-cols-2">
                  <button
                    type="button"
                    className="flex h-11 w-full items-center justify-center gap-2 rounded-lg border border-midnight-950/15 text-sm font-semibold text-midnight-900 hover:border-championship-500"
                    onClick={onChangePassword}
                  >
                    <KeyRound className="h-4 w-4" />
                    {t.changePassword}
                  </button>
                  <button
                    type="button"
                    className="flex h-11 w-full items-center justify-center gap-2 rounded-lg border border-midnight-950/15 text-sm font-semibold text-midnight-900 hover:border-championship-500"
                    onClick={onLogout}
                  >
                    <LogOut className="h-4 w-4" />
                    {t.signOut}
                  </button>
                </div>
              </div>
            ) : (
              <form onSubmit={onAuth} className="space-y-3">
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    className={`h-10 rounded-lg text-sm font-semibold ${authMode === 'login' ? 'bg-midnight-950 text-ivory-100' : 'bg-ivory-200 text-midnight-900'}`}
                    onClick={() => setAuthMode('login')}
                  >
                    {t.signIn}
                  </button>
                  <button
                    type="button"
                    className={`h-10 rounded-lg text-sm font-semibold ${authMode === 'register' ? 'bg-midnight-950 text-ivory-100' : 'bg-ivory-200 text-midnight-900'}`}
                    onClick={() => setAuthMode('register')}
                  >
                    {t.register}
                  </button>
                </div>
                <Field label={t.email} value={authForm.email} onChange={(email) => setAuthForm({ ...authForm, email })} type="email" required />
                <PasswordField
                  label={t.password}
                  value={authForm.password}
                  onChange={(password) => setAuthForm({ ...authForm, password })}
                  visible={showPassword}
                  onToggle={() => setShowPassword((current) => !current)}
                  showLabel={t.showPassword}
                  hideLabel={t.hidePassword}
                  required
                />
                {authMode === 'register' && (
                  <>
                    <Field label={t.name} value={authForm.displayName} onChange={(displayName) => setAuthForm({ ...authForm, displayName })} required />
                    <p className="rounded-lg bg-ivory-200 px-3 py-2 text-xs leading-5 text-slateInk">{t.registrationLoadedEmailHint}</p>
                  </>
                )}
                <button
                  className="flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-championship-500 font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
                  disabled={busy}
                >
                  {authMode === 'login' ? <LogIn className="h-4 w-4" /> : <UserPlus className="h-4 w-4" />}
                  {authMode === 'login' ? t.signIn : t.register}
                </button>
                {authMode === 'login' && (
                  <button
                    type="button"
                    className="mx-auto block text-xs font-semibold text-slateInk underline-offset-4 hover:text-midnight-950 hover:underline"
                    onClick={onForgotPassword}
                  >
                    {t.forgotPassword}
                  </button>
                )}
              </form>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

function NavButton({ active, onClick, icon, label }: { active: boolean; onClick: () => void; icon: React.ReactNode; label: string }) {
  return (
    <button
      className={`flex h-10 items-center gap-2 rounded-lg border px-3 text-sm font-semibold ${
        active
          ? 'border-championship-500 bg-championship-500 text-midnight-950'
          : 'border-ivory-100/20 text-ivory-100 hover:border-championship-400 hover:text-championship-400'
      }`}
      onClick={onClick}
    >
      <span className="h-4 w-4">{icon}</span>
      {label}
    </button>
  );
}

function Field({
  label,
  value,
  onChange,
  type = 'text',
  disabled = false,
  required = false
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  disabled?: boolean;
  required?: boolean;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-[0.14em] text-slateInk">{label}</span>
      <input
        className="h-11 w-full rounded-lg border border-midnight-950/15 bg-white px-3 text-sm outline-none ring-championship-500 focus:ring-2 disabled:bg-ivory-200 disabled:text-slateInk"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        type={type}
        disabled={disabled}
        required={required}
      />
    </label>
  );
}

function PasswordField({
  label,
  value,
  onChange,
  visible,
  onToggle,
  showLabel,
  hideLabel,
  required = false
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  visible: boolean;
  onToggle: () => void;
  showLabel: string;
  hideLabel: string;
  required?: boolean;
}) {
  const buttonLabel = visible ? hideLabel : showLabel;

  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-[0.14em] text-slateInk">{label}</span>
      <div className="relative">
        <input
          className="h-11 w-full rounded-lg border border-midnight-950/15 bg-white px-3 pr-12 text-sm outline-none ring-championship-500 focus:ring-2"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          type={visible ? 'text' : 'password'}
          required={required}
        />
        <button
          type="button"
          className="absolute inset-y-0 right-0 flex w-11 items-center justify-center text-slateInk hover:text-midnight-950"
          onClick={onToggle}
          aria-label={buttonLabel}
          title={buttonLabel}
        >
          {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </button>
      </div>
    </label>
  );
}

function PasswordDialog({
  t,
  mode,
  form,
  setForm,
  busy,
  onSubmit,
  onClose
}: {
  t: Record<string, string>;
  mode: PasswordDialogMode;
  form: typeof defaultPasswordForm;
  setForm: (form: typeof defaultPasswordForm) => void;
  busy: boolean;
  onSubmit: (event: FormEvent) => void;
  onClose: () => void;
}) {
  const title = mode === 'change' ? t.changePassword : mode === 'forgot' ? t.resetPassword : t.chooseNewPassword;
  const intro = mode === 'change' ? t.changePasswordIntro : mode === 'forgot' ? t.forgotPasswordIntro : t.resetPasswordIntro;
  const action = mode === 'change' ? t.savePassword : mode === 'forgot' ? t.sendResetLink : t.resetPassword;

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-midnight-950/70 px-4 py-6 backdrop-blur-sm">
      <form onSubmit={onSubmit} className="w-full max-w-md rounded-lg border border-midnight-950/10 bg-ivory-100 p-5 shadow-card">
        <div className="mb-4 flex items-start justify-between gap-3">
          <div>
            <p className="text-xs uppercase tracking-[0.18em] text-championship-600">Vlugboek</p>
            <h2 className="mt-1 font-display text-3xl text-midnight-950">{title}</h2>
          </div>
          <button type="button" className="rounded-lg px-2 py-1 text-sm font-semibold text-slateInk hover:text-midnight-950" onClick={onClose}>
            {t.close}
          </button>
        </div>
        <p className="mb-4 text-sm leading-6 text-slateInk">{intro}</p>

        <div className="space-y-3">
          {mode !== 'change' && (
            <Field label={t.email} value={form.email} onChange={(email) => setForm({ ...form, email })} type="email" required />
          )}
          {mode === 'change' && (
            <Field label={t.currentPassword} value={form.currentPassword} onChange={(currentPassword) => setForm({ ...form, currentPassword })} type="password" required />
          )}
          {mode !== 'forgot' && (
            <>
              <Field label={t.newPassword} value={form.newPassword} onChange={(newPassword) => setForm({ ...form, newPassword })} type="password" required />
              <Field label={t.confirmPassword} value={form.confirmPassword} onChange={(confirmPassword) => setForm({ ...form, confirmPassword })} type="password" required />
            </>
          )}
        </div>

        <div className="mt-5 grid gap-2 sm:grid-cols-[1fr_auto]">
          <button
            className="flex h-11 items-center justify-center gap-2 rounded-lg bg-championship-500 px-4 font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
            disabled={busy}
          >
            <KeyRound className="h-4 w-4" />
            {action}
          </button>
          <button
            type="button"
            className="h-11 rounded-lg border border-midnight-950/15 px-4 text-sm font-semibold text-midnight-900 hover:border-championship-500"
            onClick={onClose}
          >
            {t.cancel}
          </button>
        </div>
      </form>
    </div>
  );
}

function SelectField({
  label,
  value,
  options,
  onChange,
  disabled = false,
  required = false,
  placeholder = '-'
}: {
  label: string;
  value: string;
  options: LabelDto[];
  onChange: (value: string) => void;
  disabled?: boolean;
  required?: boolean;
  placeholder?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-[0.14em] text-slateInk">{label}</span>
      <select
        className="h-11 w-full rounded-lg border border-midnight-950/15 bg-white px-3 text-sm outline-none ring-championship-500 focus:ring-2 disabled:bg-ivory-200 disabled:text-slateInk"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        required={required}
      >
        <option value="">{placeholder}</option>
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.code ? `${option.code} - ${option.name}` : option.name}
          </option>
        ))}
      </select>
    </label>
  );
}

function QuickStats({ dashboard, t }: { dashboard: DashboardDto | null; t: Record<string, string> }) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <StatCard icon={<FileText />} label={t.documents} value={dashboard?.documentCount ?? 0} accent="text-championship-600" />
      <StatCard icon={<Timer />} label={t.races} value={dashboard?.raceCount ?? 0} accent="text-field" />
      <StatCard icon={<Trophy />} label={t.snapshots} value={dashboard?.leaderboardCount ?? 0} accent="text-burgundy" />
      <StatCard icon={<Flag />} label={t.federations} value={dashboard?.federationCount ?? 0} accent="text-midnight-800" />
    </div>
  );
}

function StatCard({ icon, label, value, accent }: { icon: React.ReactNode; label: string; value: number; accent: string }) {
  return (
    <div className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
      <div className={`mb-3 h-5 w-5 ${accent}`}>{icon}</div>
      <p className="text-2xl font-semibold text-midnight-950">{value}</p>
      <p className="mt-1 text-xs uppercase tracking-[0.14em] text-slateInk">{label}</p>
    </div>
  );
}

function MiniStat({ icon, label, value }: { icon: React.ReactNode; label: string; value: number }) {
  return (
    <div className="rounded-lg border border-midnight-950/10 bg-ivory-200 p-3">
      <div className="mb-2 h-4 w-4 text-championship-600">{icon}</div>
      <p className="font-semibold">{value}</p>
      <p className="text-[11px] uppercase tracking-[0.12em] text-slateInk">{label}</p>
    </div>
  );
}

function ReportList({
  t,
  documents,
  selected,
  filters,
  setFilters,
  clearFilters,
  showCurrent,
  racePoints,
  federations,
  clubs,
  lofts,
  isAdmin,
  loading,
  onSelect
}: {
  t: Record<string, string>;
  documents: DocumentDto[];
  selected: DocumentDto | null;
  filters: ReportFilters;
  setFilters: (filters: Partial<ReportFilters>) => void;
  clearFilters: () => void;
  showCurrent: () => void;
  racePoints: string[];
  federations: LabelDto[];
  clubs: LabelDto[];
  lofts: LabelDto[];
  isAdmin: boolean;
  loading: boolean;
  onSelect: (document: DocumentDto) => void;
}) {
  const hasFilters = Object.values(filters).some(Boolean);
  const quickClass = (active: boolean) =>
    `flex h-9 min-w-0 items-center justify-center rounded-lg border px-2 text-xs font-semibold ${
      active
        ? 'border-midnight-950 bg-midnight-950 text-ivory-100'
        : 'border-midnight-950/10 bg-ivory-100 text-midnight-900 hover:border-championship-500'
    }`;

  return (
    <div className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-display text-2xl">{t.recentReports}</h2>
        <FileText className="h-5 w-5 text-championship-600" />
      </div>
      <label className="relative block">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slateInk" />
        <input
          className="h-11 w-full rounded-lg border border-midnight-950/10 bg-ivory-100 pl-10 pr-3 text-sm outline-none ring-championship-500 focus:ring-2"
          value={filters.query ?? ''}
          onChange={(event) => setFilters({ query: event.target.value })}
          placeholder={t.search}
        />
      </label>
      <div className="mt-3 grid grid-cols-4 gap-2">
        <button className={quickClass(filters.family === 'RACE_DETAIL' && !filters.category)} onClick={showCurrent}>
          {t.current}
        </button>
        <button className={quickClass(!filters.family && !filters.category)} onClick={clearFilters}>
          {t.all}
        </button>
        <button className={quickClass(filters.family === 'CLASSIFICATION')} onClick={() => setFilters({ family: 'CLASSIFICATION', category: '', racePoint: '' })}>
          {t.points}
        </button>
        <button className={quickClass(filters.family === 'DISTANCE_LOG')} onClick={() => setFilters({ family: 'DISTANCE_LOG', category: '', racePoint: '' })}>
          {t.distance}
        </button>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-2">
        <FilterSelect
          label={t.type}
          value={filters.family ?? ''}
          onChange={(family) => setFilters({ family })}
        >
          {familyOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {t[option.labelKey]}
            </option>
          ))}
        </FilterSelect>

        <FilterSelect
          label={t.category}
          value={filters.category ?? ''}
          onChange={(category) => setFilters({ category })}
        >
          {categoryOptions.map((category) => (
            <option key={category || 'all'} value={category}>
              {category ? formatCategory(category) : t.allCategories}
            </option>
          ))}
        </FilterSelect>

        <FilterInput
          label={t.dateFrom}
          value={filters.dateFrom ?? ''}
          onChange={(dateFrom) => setFilters({ dateFrom })}
        />
        <FilterInput
          label={t.dateTo}
          value={filters.dateTo ?? ''}
          onChange={(dateTo) => setFilters({ dateTo })}
        />

        <FilterSelect
          label={t.racePoint}
          value={filters.racePoint ?? ''}
          onChange={(racePoint) => setFilters({ racePoint })}
        >
          <option value="">{t.allRacePoints}</option>
          {racePoints.map((racePoint) => (
            <option key={racePoint} value={racePoint}>
              {racePoint}
            </option>
          ))}
        </FilterSelect>

        {isAdmin && (
          <FilterSelect
            label={t.federation}
            value={filters.federationId ?? ''}
            onChange={(federationId) => setFilters({ federationId, clubId: '', loftId: '' })}
          >
            <option value="">{t.allFederations}</option>
            {federations.map((federation) => (
              <option key={federation.id} value={federation.id}>
                {federation.code ? `${federation.code} - ${federation.name}` : federation.name}
              </option>
            ))}
          </FilterSelect>
        )}

        {isAdmin && (
          <FilterSelect
            label={t.club}
            value={filters.clubId ?? ''}
            onChange={(clubId) => setFilters({ clubId, loftId: '' })}
            disabled={!filters.federationId}
          >
            <option value="">{t.allClubs}</option>
            {clubs.map((club) => (
              <option key={club.id} value={club.id}>
                {club.name}
              </option>
            ))}
          </FilterSelect>
        )}

        {isAdmin && (
          <FilterSelect
            label={t.loft}
            value={filters.loftId ?? ''}
            onChange={(loftId) => setFilters({ loftId })}
            disabled={!filters.clubId}
          >
            <option value="">{t.allLofts}</option>
            {lofts.map((loft) => (
              <option key={loft.id} value={loft.id}>
                {loft.name}
              </option>
            ))}
          </FilterSelect>
        )}
      </div>

      {hasFilters && (
        <button
          className="mt-3 h-9 w-full rounded-lg border border-midnight-950/10 text-sm font-semibold text-midnight-900 hover:border-championship-500"
          onClick={clearFilters}
        >
          {t.clearFilters}
        </button>
      )}

      <div className="mt-4 max-h-[560px] space-y-2 overflow-y-auto pr-1">
        {loading && <LoadingRows />}
        {!loading && documents.length === 0 && <p className="rounded-lg bg-ivory-100 px-3 py-4 text-sm text-slateInk">{t.noReports}</p>}
        {!loading && documents.map((document) => (
          <button
            key={document.id}
            onClick={() => onSelect(document)}
            className={`w-full rounded-lg border p-3 text-left transition ${
              selected?.id === document.id
                ? 'border-championship-500 bg-championship-400/15'
                : 'border-midnight-950/10 bg-white hover:border-championship-500/60'
            }`}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate font-semibold text-midnight-950">{document.title}</p>
                <p className="mt-1 text-xs text-slateInk">{labelFamily(document.reportFamily)}</p>
              </div>
              <span className={`rounded-lg px-2 py-1 text-[11px] font-semibold ${statusClass(document.status)}`}>
                {document.status}
              </span>
            </div>
            <p className="mt-2 text-xs text-slateInk">
              {[document.racePoint, document.officialDate ?? document.uploadedAt.slice(0, 10)].filter(Boolean).join(' / ')}
            </p>
          </button>
        ))}
      </div>
    </div>
  );
}

function FilterSelect({
  label,
  value,
  onChange,
  disabled,
  children
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="min-w-0">
      <span className="mb-1 block text-[11px] font-semibold uppercase tracking-[0.12em] text-slateInk">{label}</span>
      <select
        className="h-10 w-full rounded-lg border border-midnight-950/10 bg-ivory-100 px-2 text-xs outline-none ring-championship-500 focus:ring-2 disabled:opacity-50"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        {children}
      </select>
    </label>
  );
}

function FilterInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="min-w-0">
      <span className="mb-1 block text-[11px] font-semibold uppercase tracking-[0.12em] text-slateInk">{label}</span>
      <input
        type="date"
        className="h-10 w-full rounded-lg border border-midnight-950/10 bg-ivory-100 px-2 text-xs outline-none ring-championship-500 focus:ring-2"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function ResultsPanel({
  t,
  document,
  dataset,
  datasetQuery,
  setDatasetQuery,
  tableMode,
  setTableMode,
  onEmail,
  onError
}: {
  t: Record<string, string>;
  document: DocumentDto;
  dataset: DatasetDto;
  datasetQuery: string;
  setDatasetQuery: (query: string) => void;
  tableMode: TableMode;
  setTableMode: (mode: TableMode) => void;
  onEmail: (document: DocumentDto) => void;
  onError: (error: unknown) => void;
}) {
  return (
    <section className="rounded-lg border border-midnight-950/10 bg-white shadow-card">
      <div className="border-b border-midnight-950/10 p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-xs uppercase tracking-[0.18em] text-championship-600">{document.recognisedType}</p>
            <h2 className="mt-1 font-display text-3xl text-midnight-950 sm:text-4xl">{document.title}</h2>
            <div className="mt-3 flex flex-wrap gap-2 text-xs text-slateInk">
              <Meta label={t.officialDate} value={document.officialDate ?? '-'} />
              <Meta label={t.recognised} value={labelFamily(document.reportFamily)} />
              <Meta label={t.imported} value={document.status} />
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <IconButton label={t.pdf} icon={<Eye />} onClick={() => void openAsset(document.pdfUrl, `${safeDownloadName(document.title)}.pdf`).catch(onError)} />
            <button
              className="flex h-10 items-center gap-2 rounded-lg border border-midnight-950/10 px-3 text-sm font-semibold text-midnight-900 hover:border-championship-500"
              onClick={() => void downloadAsset(document.csvUrl, `${safeDownloadName(document.title)}.csv`).catch(onError)}
            >
              <Download className="h-4 w-4" />
              {t.csv}
            </button>
            <IconButton label={t.emailPdf} icon={<Mail />} onClick={() => onEmail(document)} />
          </div>
        </div>
        <div className="mt-5 grid gap-3 md:grid-cols-[1fr_auto] md:items-end">
          <label className="relative block">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slateInk" />
            <input
              className="h-10 w-full rounded-lg border border-midnight-950/10 bg-ivory-100 pl-10 pr-3 text-sm outline-none ring-championship-500 focus:ring-2"
              value={datasetQuery}
              onChange={(event) => setDatasetQuery(event.target.value)}
              placeholder={t.searchRows}
            />
          </label>
          <div className="inline-grid grid-cols-2 rounded-lg border border-midnight-950/10 bg-ivory-100 p-1">
            <button
              className={`flex h-9 items-center justify-center gap-2 rounded-md px-3 text-sm font-semibold ${tableMode === 'table' ? 'bg-midnight-950 text-ivory-100' : 'text-midnight-900'}`}
              onClick={() => setTableMode('table')}
            >
              <Table2 className="h-4 w-4" />
              {t.table}
            </button>
            <button
              className={`flex h-9 items-center justify-center gap-2 rounded-md px-3 text-sm font-semibold ${tableMode === 'cards' ? 'bg-midnight-950 text-ivory-100' : 'text-midnight-900'}`}
              onClick={() => setTableMode('cards')}
            >
              <LayoutGrid className="h-4 w-4" />
              {t.cards}
            </button>
          </div>
        </div>
      </div>
      <ResultData t={t} dataset={dataset} mode={tableMode} query={datasetQuery} />
    </section>
  );
}

function ResultData({ t, dataset, mode, query }: { t: Record<string, string>; dataset: DatasetDto; mode: TableMode; query: string }) {
  const normalisedQuery = normaliseText(query);
  const rows = normalisedQuery
    ? dataset.rows.filter((row) => row.some((cell) => normaliseText(cell).includes(normalisedQuery)))
    : dataset.rows;

  if (!dataset.rows.length) {
    return <p className="p-6 text-sm text-slateInk">{t.noRows}</p>;
  }
  if (!rows.length) {
    return <EmptyPanel icon={<Search />} title={t.noMatchingRows} />;
  }

  if (mode === 'cards') {
    return (
      <div className="grid gap-3 p-4 sm:grid-cols-2 xl:grid-cols-3">
        {rows.map((row, rowIndex) => (
          <article key={`${row[0]}-${rowIndex}`} className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-sm">
            <div className="mb-3 flex items-start justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                <RankBadge rank={Number(row[0]) || rowIndex + 1} />
                <div className="min-w-0">
                  <p className="truncate font-semibold text-midnight-950">{row[1] ?? '-'}</p>
                  <p className="text-xs text-slateInk">{dataset.columns[1] ?? t.results}</p>
                </div>
              </div>
              <span className="rounded-lg bg-ivory-200 px-2 py-1 text-xs font-semibold text-midnight-950">{row[row.length - 1]}</span>
            </div>
            <div className="grid gap-2">
              {dataset.columns.slice(2).map((column, offset) => {
                const value = row[offset + 2];
                if (!value) return null;
                return (
                  <div key={column} className="grid grid-cols-[minmax(84px,0.72fr)_1fr] gap-3 rounded-md bg-ivory-100 px-3 py-2">
                    <span className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slateInk">{column}</span>
                    <span className="min-w-0 break-words text-right text-sm font-semibold text-midnight-950">{value}</span>
                  </div>
                );
              })}
            </div>
          </article>
        ))}
      </div>
    );
  }

  return (
    <div className="result-scroll overflow-x-auto p-4">
      <table className="w-full min-w-[780px] border-separate border-spacing-0 text-left text-sm">
        <thead>
          <tr>
            {dataset.columns.map((column, index) => (
              <th
                key={column}
                className={`border-b border-midnight-950 bg-midnight-950 px-3 py-3 font-semibold text-ivory-100 first:rounded-l-lg last:rounded-r-lg ${
                  index === 0 ? 'sticky left-0 z-10 text-championship-400' : ''
                }`}
              >
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex} className={rowIndex % 2 === 0 ? 'bg-white' : 'bg-ivory-100'}>
              {row.map((cell, columnIndex) => (
                <td
                  key={`${rowIndex}-${columnIndex}`}
                  className={`border-b border-midnight-950/10 px-3 py-3 text-midnight-900 ${
                    columnIndex === 0 ? 'sticky left-0 z-[1] bg-inherit font-bold text-championship-600' : ''
                  }`}
                >
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EmptyPanel({ icon, title, actionLabel, onAction }: { icon: React.ReactNode; title: string; actionLabel?: string; onAction?: () => void }) {
  return (
    <div className="rounded-lg border border-midnight-950/10 bg-white p-6 text-center shadow-card">
      <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-lg bg-ivory-200 text-championship-600">
        <span className="h-6 w-6">{icon}</span>
      </div>
      <p className="font-semibold text-midnight-950">{title}</p>
      {actionLabel && onAction && (
        <button
          className="mt-4 inline-flex h-10 items-center justify-center rounded-lg bg-midnight-950 px-4 text-sm font-semibold text-ivory-100 hover:bg-midnight-800"
          onClick={onAction}
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
}

function LoadingPanel({ t, message }: { t: Record<string, string>; message: string }) {
  return (
    <div className="rounded-lg border border-midnight-950/10 bg-white p-5 shadow-card">
      <div className="mb-4 h-5 w-40 animate-pulse rounded-md bg-ivory-200" />
      <div className="mb-3 h-9 w-3/4 animate-pulse rounded-md bg-ivory-200" />
      <div className="h-4 w-52 animate-pulse rounded-md bg-ivory-200" />
      <p className="mt-5 text-sm text-slateInk">{message || t.processing}</p>
    </div>
  );
}

function LoadingRows() {
  return (
    <div className="space-y-2">
      {[0, 1, 2].map((index) => (
        <div key={index} className="rounded-lg border border-midnight-950/10 bg-white p-3">
          <div className="h-4 w-2/3 animate-pulse rounded bg-ivory-200" />
          <div className="mt-3 h-3 w-1/2 animate-pulse rounded bg-ivory-200" />
        </div>
      ))}
    </div>
  );
}

function LeaderboardsPanel({ t, leaderboards }: { t: Record<string, string>; leaderboards: LeaderboardDto[] }) {
  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
        <div>
          <p className="text-xs uppercase tracking-[0.18em] text-championship-600">PWDF</p>
          <h2 className="font-display text-3xl">{t.currentLeaderboards}</h2>
        </div>
        <Trophy className="h-8 w-8 text-championship-500" />
      </div>
      <div className="grid gap-4 xl:grid-cols-2">
        {leaderboards.map((board) => (
          <article key={board.category} className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
            <div className="mb-4 flex items-start justify-between">
              <div>
                <p className="text-xs uppercase tracking-[0.16em] text-slateInk">{formatCategory(board.category)}</p>
                <h3 className="font-display text-2xl">{board.title}</h3>
              </div>
              <span className="rounded-lg bg-ivory-200 px-3 py-1 text-xs font-semibold text-midnight-900">{board.snapshotDate}</span>
            </div>
            <div className="space-y-2">
              {board.rows.slice(0, 8).map((row, index) => (
                <div key={`${board.category}-${index}`} className="grid grid-cols-[52px_1fr_auto] items-center gap-3 rounded-lg border border-midnight-950/10 px-3 py-3">
                  <RankBadge rank={index + 1} />
                  <div className="min-w-0">
                    <p className="truncate font-semibold text-midnight-950">{row[1]}</p>
                    <p className="text-xs text-slateInk">{row[2]}</p>
                  </div>
                  <p className="font-semibold text-midnight-950">{row[row.length - 1]}</p>
                </div>
              ))}
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function UploadPanel({
  t,
  busy,
  uploadFile,
  setUploadFile,
  pendingImport,
  onUpload,
  onConfirm,
  documents,
  onSelect
}: {
  t: Record<string, string>;
  busy: boolean;
  uploadFile: File | null;
  setUploadFile: (file: File | null) => void;
  pendingImport: UploadResponse | null;
  onUpload: () => void;
  onConfirm: () => void;
  documents: DocumentDto[];
  onSelect: (document: DocumentDto) => void;
}) {
  return (
    <section className="grid gap-4 xl:grid-cols-[1fr_360px]">
      <div className="rounded-lg border border-midnight-950/10 bg-white p-5 shadow-card">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.18em] text-championship-600">{t.processing}</p>
            <h2 className="font-display text-3xl">{t.uploadPdf}</h2>
          </div>
          <UploadCloud className="h-8 w-8 text-championship-500" />
        </div>

        <label className="flex min-h-[220px] cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-championship-500/70 bg-ivory-100 px-5 text-center hover:bg-championship-400/10">
          <FileDown className="mb-3 h-10 w-10 text-championship-600" />
          <span className="font-semibold text-midnight-950">{uploadFile?.name ?? t.choosePdf}</span>
          <input
            type="file"
            accept="application/pdf"
            className="hidden"
            onChange={(event) => setUploadFile(event.target.files?.[0] ?? null)}
          />
        </label>

        <button
          className="mt-4 flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-midnight-950 font-semibold text-ivory-100 hover:bg-midnight-800 disabled:opacity-60"
          disabled={!uploadFile || busy}
          onClick={onUpload}
        >
          <ClipboardCheck className="h-4 w-4" />
          {busy ? t.processing : t.reviewImport}
        </button>

        {pendingImport && (
          <div className="mt-5 rounded-lg border border-championship-500/50 bg-ivory-100 p-4">
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-xs uppercase tracking-[0.18em] text-championship-600">{t.staged}</p>
                <h3 className="mt-1 font-display text-2xl text-midnight-950">{pendingImport.document.title}</h3>
              </div>
              <span className={`rounded-lg px-3 py-1 text-xs font-semibold ${statusClass(pendingImport.document.status)}`}>
                {pendingImport.document.status}
              </span>
            </div>

            <div className="mb-4 flex flex-wrap gap-2 text-xs text-slateInk">
              <Meta label={t.officialDate} value={pendingImport.document.officialDate ?? '-'} />
              <Meta label={t.recognised} value={pendingImport.document.recognisedType} />
              <Meta label={t.rowCount} value={String(pendingImport.dataset.rows.length)} />
              <Meta label={t.columnCount} value={String(pendingImport.dataset.columns.length)} />
            </div>

            <div className="result-scroll overflow-x-auto rounded-lg border border-midnight-950/10 bg-white">
              <table className="w-full min-w-[640px] border-separate border-spacing-0 text-left text-sm">
                <thead>
                  <tr>
                    {pendingImport.dataset.columns.map((column) => (
                      <th key={column} className="border-b border-midnight-950/10 bg-midnight-950 px-3 py-2 font-semibold text-ivory-100">
                        {column}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pendingImport.dataset.rows.slice(0, 5).map((row, rowIndex) => (
                    <tr key={rowIndex} className={rowIndex % 2 === 0 ? 'bg-white' : 'bg-ivory-100'}>
                      {row.map((cell, columnIndex) => (
                        <td key={`${rowIndex}-${columnIndex}`} className="border-b border-midnight-950/10 px-3 py-2 text-midnight-900">
                          {cell}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <button
              className="mt-4 flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-championship-500 font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
              disabled={busy}
              onClick={onConfirm}
            >
              <ClipboardCheck className="h-4 w-4" />
              {busy ? t.processing : t.confirmImport}
            </button>
          </div>
        )}
      </div>

      <div className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
        <h3 className="mb-3 font-display text-2xl">{t.documents}</h3>
        <div className="space-y-2">
          {documents.length === 0 && <p className="rounded-lg bg-ivory-100 px-3 py-4 text-sm text-slateInk">{t.noReports}</p>}
          {documents.slice(0, 8).map((document) => (
            <button
              key={document.id}
              onClick={() => onSelect(document)}
              className="w-full rounded-lg border border-midnight-950/10 px-3 py-3 text-left hover:border-championship-500"
            >
              <div className="flex items-start justify-between gap-2">
                <p className="min-w-0 truncate font-semibold">{document.title}</p>
                <span className={`shrink-0 rounded-lg px-2 py-1 text-[11px] font-semibold ${statusClass(document.status)}`}>{document.status}</span>
              </div>
              <p className="mt-1 text-xs text-slateInk">{document.racePoint ?? document.recognisedType}</p>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

function OrganisationAdminPanel({
  t,
  user,
  tree,
  busy,
  onChanged,
  onError
}: {
  t: Record<string, string>;
  user: AuthResponse;
  tree: OrganisationTreeDto | null;
  busy: boolean;
  onChanged: () => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const systemAdmin = isSystemAdminUser(user);

  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      await onChanged();
    } catch (error) {
      onError(error);
    }
  }

  function addFederation(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.createFederation({ code, name });
      setCode('');
      setName('');
    });
  }

  return (
    <section className="space-y-4">
      <div className="rounded-lg border border-midnight-950/10 bg-white p-4 shadow-card">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs uppercase tracking-[0.18em] text-championship-600">{systemAdmin ? t.systemAdmin : t.federationAdmin}</p>
            <h2 className="font-display text-3xl">{t.organisations}</h2>
          </div>
          <Building2 className="h-8 w-8 text-championship-500" />
        </div>
        {systemAdmin && (
          <form onSubmit={addFederation} className="mt-4 grid gap-3 md:grid-cols-[120px_minmax(0,1fr)_auto]">
            <Field label={t.code} value={code} onChange={setCode} required />
            <Field label={t.name} value={name} onChange={setName} required />
            <button
              className="flex h-11 items-center justify-center gap-2 self-end rounded-lg bg-midnight-950 px-4 text-sm font-semibold text-ivory-100 hover:bg-midnight-800 disabled:opacity-60"
              disabled={busy}
            >
              <Plus className="h-4 w-4" />
              {t.addFederation}
            </button>
          </form>
        )}
      </div>

      {!tree && <LoadingPanel t={t} message={t.loadingOrganisations} />}
      {tree && tree.federations.length === 0 && <EmptyPanel icon={<Building2 />} title={t.noOrganisations} />}
      {tree?.federations.map((federation) => (
        <FederationEditor
          key={federation.id}
          t={t}
          federation={federation}
          busy={busy}
          canManageFederation={systemAdmin}
          canSetFederationAdmin={systemAdmin}
          run={run}
        />
      ))}
    </section>
  );
}

function FederationEditor({
  t,
  federation,
  busy,
  canManageFederation,
  canSetFederationAdmin,
  run
}: {
  t: Record<string, string>;
  federation: FederationAdminDto;
  busy: boolean;
  canManageFederation: boolean;
  canSetFederationAdmin: boolean;
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const [code, setCode] = useState(federation.code);
  const [name, setName] = useState(federation.name);
  const [adminEmail, setAdminEmail] = useState(federation.federationAdmin?.email ?? '');
  const [clubName, setClubName] = useState('');
  const [preloadEmail, setPreloadEmail] = useState('');
  const [preloadClubId, setPreloadClubId] = useState('');
  const [preloadLoftId, setPreloadLoftId] = useState('');

  useEffect(() => {
    setCode(federation.code);
    setName(federation.name);
    setAdminEmail(federation.federationAdmin?.email ?? '');
  }, [federation.id, federation.code, federation.name, federation.federationAdmin?.email]);

  const changed = code.trim() !== federation.code || name.trim() !== federation.name;
  const canDelete = !federation.locked && federation.clubCount === 0;
  const selectedPreloadClub = federation.clubs.find((club) => String(club.id) === preloadClubId);

  function saveFederation(event: FormEvent) {
    event.preventDefault();
    if (!canManageFederation) return;
    void run(() => api.updateFederation(federation.id, { code, name }));
  }

  function deleteFederation() {
    if (!canManageFederation || !canDelete || !window.confirm(t.deleteConfirm)) return;
    void run(() => api.deleteFederation(federation.id));
  }

  function saveFederationAdmin(event: FormEvent) {
    event.preventDefault();
    if (!canSetFederationAdmin) return;
    void run(() => api.setFederationAdmin(federation.id, { email: adminEmail }));
  }

  function addClub(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.createClub({ federationId: federation.id, name: clubName });
      setClubName('');
    });
  }

  function preloadFancier(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.preloadUser({
        email: preloadEmail,
        federationId: federation.id,
        clubId: Number(preloadClubId),
        loftId: Number(preloadLoftId)
      });
      setPreloadEmail('');
      setPreloadClubId('');
      setPreloadLoftId('');
    });
  }

  return (
    <article className="rounded-lg border border-midnight-950/10 bg-white shadow-card">
      <div className="p-4">
        {canManageFederation ? (
          <form onSubmit={saveFederation} className="grid gap-3 lg:grid-cols-[120px_minmax(0,1fr)_auto]">
            <Field label={t.code} value={code} onChange={setCode} disabled={federation.locked} required />
            <Field label={t.federation} value={name} onChange={setName} disabled={federation.locked} required />
            <div className="flex items-end gap-2">
              <button
                className="flex h-11 items-center justify-center gap-2 rounded-lg bg-championship-500 px-4 text-sm font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
                disabled={busy || federation.locked || !changed}
              >
                <Save className="h-4 w-4" />
                {t.save}
              </button>
              <button
                type="button"
                title={t.delete}
                className="flex h-11 w-11 items-center justify-center rounded-lg border border-burgundy/30 text-burgundy hover:border-burgundy disabled:opacity-40"
                onClick={deleteFederation}
                disabled={busy || !canDelete}
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          </form>
        ) : (
          <div>
            <p className="text-xs uppercase tracking-[0.18em] text-championship-600">{federation.code}</p>
            <h3 className="font-display text-3xl">{federation.name}</h3>
          </div>
        )}
        <div className="mt-3 flex flex-wrap gap-2">
          <OrgMetric label={t.users} value={federation.userCount} />
          <OrgMetric label={t.reports} value={federation.documentCount} />
          <OrgMetric label={t.clubs} value={federation.clubCount} />
          {federation.locked && <span className="rounded-lg bg-championship-400/20 px-3 py-1 text-xs font-semibold text-midnight-900">{t.linked}</span>}
        </div>
        {canSetFederationAdmin && (
          <form onSubmit={saveFederationAdmin} className="mt-4 grid gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
            <Field label={t.federationAdminEmail} value={adminEmail} onChange={setAdminEmail} type="email" required />
            <button
              className="flex h-11 items-center justify-center gap-2 self-end rounded-lg border border-midnight-950/15 px-4 text-sm font-semibold text-midnight-900 hover:border-championship-500 disabled:opacity-60"
              disabled={busy || adminEmail.trim() === (federation.federationAdmin?.email ?? '')}
            >
              <Save className="h-4 w-4" />
              {t.save}
            </button>
          </form>
        )}
        {!canSetFederationAdmin && federation.federationAdmin && (
          <p className="mt-3 rounded-lg bg-ivory-200 px-3 py-2 text-sm text-slateInk">
            <strong className="text-midnight-950">{t.federationAdmin}:</strong> {federation.federationAdmin.email}
          </p>
        )}
        <form onSubmit={addClub} className="mt-4 grid gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
          <Field label={t.newClub} value={clubName} onChange={setClubName} required />
          <button
            className="flex h-11 items-center justify-center gap-2 self-end rounded-lg border border-midnight-950/15 px-4 text-sm font-semibold text-midnight-900 hover:border-championship-500 disabled:opacity-60"
            disabled={busy}
          >
            <Plus className="h-4 w-4" />
            {t.addClub}
          </button>
        </form>
        <form onSubmit={preloadFancier} className="mt-4 grid gap-3 rounded-lg border border-midnight-950/10 bg-ivory-100 p-3 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]">
          <Field label={t.loadUserEmail} value={preloadEmail} onChange={setPreloadEmail} type="email" required />
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-[0.14em] text-slateInk">{t.club}</span>
            <select
              className="h-11 w-full rounded-lg border border-midnight-950/15 bg-white px-3 text-sm outline-none ring-championship-500 focus:ring-2"
              value={preloadClubId}
              onChange={(event) => {
                setPreloadClubId(event.target.value);
                setPreloadLoftId('');
              }}
              required
            >
              <option value="">{t.chooseClub}</option>
              {federation.clubs.map((club) => (
                <option key={club.id} value={club.id}>{club.name}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-[0.14em] text-slateInk">{t.loft}</span>
            <select
              className="h-11 w-full rounded-lg border border-midnight-950/15 bg-white px-3 text-sm outline-none ring-championship-500 focus:ring-2 disabled:bg-ivory-200"
              value={preloadLoftId}
              onChange={(event) => setPreloadLoftId(event.target.value)}
              disabled={!selectedPreloadClub}
              required
            >
              <option value="">{t.chooseLoft}</option>
              {selectedPreloadClub?.lofts.map((loft) => (
                <option key={loft.id} value={loft.id}>{loft.name}</option>
              ))}
            </select>
          </label>
          <button
            className="flex h-11 items-center justify-center gap-2 self-end rounded-lg bg-midnight-950 px-4 text-sm font-semibold text-ivory-100 hover:bg-midnight-800 disabled:opacity-60"
            disabled={busy || federation.clubs.length === 0}
          >
            <UserPlus className="h-4 w-4" />
            {t.loadUser}
          </button>
        </form>
      </div>
      <div className="border-t border-midnight-950/10">
        {federation.clubs.length === 0 && <p className="px-4 py-4 text-sm text-slateInk">{t.noClubs}</p>}
        {federation.clubs.map((club) => (
          <ClubEditor key={club.id} t={t} club={club} busy={busy} run={run} />
        ))}
      </div>
    </article>
  );
}

function ClubEditor({
  t,
  club,
  busy,
  run
}: {
  t: Record<string, string>;
  club: ClubAdminDto;
  busy: boolean;
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const [name, setName] = useState(club.name);
  const [loftName, setLoftName] = useState('');

  useEffect(() => {
    setName(club.name);
  }, [club.id, club.name]);

  const changed = name.trim() !== club.name;
  const canDelete = !club.locked && club.loftCount === 0;

  function saveClub(event: FormEvent) {
    event.preventDefault();
    void run(() => api.updateClub(club.id, { name }));
  }

  function deleteClub() {
    if (!canDelete || !window.confirm(t.deleteConfirm)) return;
    void run(() => api.deleteClub(club.id));
  }

  function addLoft(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.createLoft({ clubId: club.id, name: loftName });
      setLoftName('');
    });
  }

  return (
    <div className="border-b border-midnight-950/10 bg-ivory-100/70 px-4 py-4 last:border-b-0">
      <form onSubmit={saveClub} className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_auto]">
        <Field label={t.club} value={name} onChange={setName} disabled={club.locked} required />
        <div className="flex items-end gap-2">
          <button
            className="flex h-11 items-center justify-center gap-2 rounded-lg bg-championship-500 px-4 text-sm font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
            disabled={busy || club.locked || !changed}
          >
            <Save className="h-4 w-4" />
            {t.save}
          </button>
          <button
            type="button"
            title={t.delete}
            className="flex h-11 w-11 items-center justify-center rounded-lg border border-burgundy/30 bg-white text-burgundy hover:border-burgundy disabled:opacity-40"
            onClick={deleteClub}
            disabled={busy || !canDelete}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </form>
      <div className="mt-3 flex flex-wrap gap-2">
        <OrgMetric label={t.users} value={club.userCount} />
        <OrgMetric label={t.reports} value={club.documentCount} />
        <OrgMetric label={t.lofts} value={club.loftCount} />
        {club.locked && <span className="rounded-lg bg-championship-400/20 px-3 py-1 text-xs font-semibold text-midnight-900">{t.linked}</span>}
      </div>
      <form onSubmit={addLoft} className="mt-3 grid gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
        <Field label={t.newLoft} value={loftName} onChange={setLoftName} required />
        <button
          className="flex h-11 items-center justify-center gap-2 self-end rounded-lg border border-midnight-950/15 bg-white px-4 text-sm font-semibold text-midnight-900 hover:border-championship-500 disabled:opacity-60"
          disabled={busy}
        >
          <Plus className="h-4 w-4" />
          {t.addLoft}
        </button>
      </form>
      <div className="mt-3 grid gap-2">
        {club.lofts.length === 0 && <p className="rounded-lg bg-white px-3 py-3 text-sm text-slateInk">{t.noLofts}</p>}
        {club.lofts.map((loft) => (
          <LoftEditor key={loft.id} t={t} loft={loft} busy={busy} run={run} />
        ))}
      </div>
    </div>
  );
}

function LoftEditor({
  t,
  loft,
  busy,
  run
}: {
  t: Record<string, string>;
  loft: LoftAdminDto;
  busy: boolean;
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const [name, setName] = useState(loft.name);

  useEffect(() => {
    setName(loft.name);
  }, [loft.id, loft.name]);

  const changed = name.trim() !== loft.name;

  function saveLoft(event: FormEvent) {
    event.preventDefault();
    void run(() => api.updateLoft(loft.id, { name }));
  }

  function deleteLoft() {
    if (loft.locked || !window.confirm(t.deleteConfirm)) return;
    void run(() => api.deleteLoft(loft.id));
  }

  return (
    <form onSubmit={saveLoft} className="grid gap-3 rounded-lg border border-midnight-950/10 bg-white p-3 md:grid-cols-[minmax(0,1fr)_auto]">
      <Field label={t.loft} value={name} onChange={setName} disabled={loft.locked} required />
      <div className="flex items-end gap-2">
        <button
          className="flex h-11 items-center justify-center gap-2 rounded-lg bg-championship-500 px-4 text-sm font-semibold text-midnight-950 hover:bg-championship-400 disabled:opacity-60"
          disabled={busy || loft.locked || !changed}
        >
          <Save className="h-4 w-4" />
          {t.save}
        </button>
        <button
          type="button"
          title={t.delete}
          className="flex h-11 w-11 items-center justify-center rounded-lg border border-burgundy/30 text-burgundy hover:border-burgundy disabled:opacity-40"
          onClick={deleteLoft}
          disabled={busy || loft.locked}
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>
      <div className="flex flex-wrap gap-2 md:col-span-2">
        <OrgMetric label={t.users} value={loft.userCount} />
        <OrgMetric label={t.reports} value={loft.documentCount} />
        {loft.locked && <span className="rounded-lg bg-championship-400/20 px-3 py-1 text-xs font-semibold text-midnight-900">{t.linked}</span>}
      </div>
    </form>
  );
}

function OrgMetric({ label, value }: { label: string; value: number }) {
  return (
    <span className="rounded-lg bg-ivory-200 px-3 py-1 text-xs text-slateInk">
      <strong className="text-midnight-950">{value}</strong> {label}
    </span>
  );
}

function IconButton({ label, icon, onClick }: { label: string; icon: React.ReactNode; onClick: () => void }) {
  return (
    <button
      title={label}
      className="flex h-10 items-center gap-2 rounded-lg border border-midnight-950/10 px-3 text-sm font-semibold text-midnight-900 hover:border-championship-500"
      onClick={onClick}
    >
      <span className="h-4 w-4">{icon}</span>
      {label}
    </button>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <span className="rounded-lg bg-ivory-200 px-3 py-1">
      <strong className="text-midnight-950">{label}:</strong> {value}
    </span>
  );
}

function RankBadge({ rank }: { rank: number }) {
  const className =
    rank === 1
      ? 'bg-championship-500 text-midnight-950'
      : rank === 2
        ? 'bg-slateInk text-white'
        : rank === 3
          ? 'bg-burgundy text-white'
          : 'bg-ivory-200 text-midnight-950';
  return <span className={`flex h-9 w-9 items-center justify-center rounded-lg font-bold ${className}`}>{rank}</span>;
}

function labelFamily(family: DocumentDto['reportFamily']) {
  return family
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function safeDownloadName(value: string) {
  return value.replace(/[^A-Za-z0-9.-]+/g, '-').replace(/^-+|-+$/g, '') || 'vlugboek-report';
}

function formatCategory(category: string) {
  return category
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function statusClass(status: string) {
  switch (status) {
    case 'FAILED':
      return 'bg-burgundy text-white';
    case 'RECOGNISED':
      return 'bg-championship-500 text-midnight-950';
    case 'IMPORTED':
      return 'bg-field text-white';
    default:
      return 'bg-midnight-950 text-ivory-100';
  }
}

function normaliseText(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}
