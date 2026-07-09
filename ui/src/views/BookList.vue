<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

interface BookSpec {
  bookId: string
  title: string
  author: string
  cover: string
  intro: string
  publisher: string
  publishTime: string
  isbn: string
  category: string
  totalWords: number
  readInfo: number
  progress: number
  readingTime: number
  noteCount: number
  reviewCount: number
  lastReadTime: number
  pcUrl?: string
  finishTime?: number
  hidden?: boolean
}

interface BookItem {
  metadata: { name: string }
  spec: BookSpec
}

interface Bookmark {
  markId: string
  content: string
  colorStyle: number
  createTime: number
}

interface Review {
  markId: string
  content: string
  abstractContent: string
  createTime: number
}

interface Chapter {
  chapterUid: number
  chapterTitle: string
  bookmarks: Bookmark[]
  reviews: Review[]
}

interface BookNotes {
  chapters: Chapter[]
  bookReviews: { markId: string; content: string; createTime: number }[]
  totalBookmarks: number
  totalReviews: number
  total: number
}

type StatsRange = 'week' | 'month' | 'year' | 'all'

interface RangeOption {
  value: StatsRange
  label: string
}

interface StatCardItem {
  label: string
  value: string
  unit: string
  icon: string
  compare?: {
    text: string
    up: boolean
  }
}

interface ChartPoint {
  key: string
  label: string
  value: number
  future?: boolean
}

interface HeatmapCell {
  key: string
  label: string
  value: number
  level: number
  empty: boolean
}

interface HeatmapWeek {
  key: string
  cells: HeatmapCell[]
}

interface HeatmapMonthLabel {
  month: number
  column: number
}

interface PreferenceItem {
  label: string
  value: number
  percent: number
}

interface ReadingStatItem {
  stat: string
  counts: string
}

interface ReadingLongestItem {
  book?: {
    bookId: string
    title: string
    author: string
    cover?: string
  }
  albumInfo?: {
    albumId: string
    name: string
    authorName: string
    cover?: string
  }
  readTime: number
  tags: string[]
}

interface ReadingCategoryPref {
  categoryTitle: string
  parentCategoryTitle: string
  readingCount: number
  readingTime: number
}

interface ReadingAuthorPref {
  name: string
  count: number
  readTime: string
}

interface ReadingStatsResponse {
  totalReadTime: number
  readDays: number
  dayAverageReadTime: number
  compare?: number
  readStat: ReadingStatItem[]
  readLongest: ReadingLongestItem[]
  readTimes: Record<string, number>
  preferCategory: ReadingCategoryPref[]
  preferTime?: number[]
  preferAuthor?: ReadingAuthorPref[]
}

interface TopReadingBook {
  id: string
  title: string
  author: string
  category: string
  cover: string
  readingMinutes: number
  progress: number
}

type StatsChartView = 'heatmap' | 'bar'

const books = ref<BookItem[]>([])
const loading = ref(false)
const searchQuery = ref('')
const categoryFilter = ref('all')
const visibilityFilter = ref('all')
const readStateFilter = ref('all')
const showReadingStats = ref(false)
const statsRange = ref<StatsRange>('year')
const selectedStatsYear = ref(new Date().getFullYear())
const statsChartView = ref<StatsChartView>('heatmap')
const officialStats = ref<ReadingStatsResponse | null>(null)
const statsLoading = ref(false)
const statsError = ref('')

// 侧边抽屉
const drawerOpen = ref(false)
const drawerBook = ref<BookItem | null>(null)
const drawerNotes = ref<BookNotes | null>(null)
const notesLoading = ref(false)
const notesError = ref('')

const toast = ref({
  show: false,
  message: '',
  type: 'success'
})

const showToast = (msg: string, type: 'success' | 'error' = 'success') => {
  toast.value.message = msg
  toast.value.type = type
  toast.value.show = true
  setTimeout(() => {
    toast.value.show = false
  }, 3000)
}

const totalBooks = computed(() => books.value.length)
const totalNotes = computed(() => books.value.reduce((s, b) => s + (b.spec.noteCount || 0), 0))
const totalReviews = computed(() => books.value.reduce((s, b) => s + (b.spec.reviewCount || 0), 0))
const totalReadingMinutes = computed(() => books.value.reduce((s, b) => s + normalizeNumber(b.spec.readingTime), 0))
const finishedBooks = computed(() => books.value.filter((book) => isFinished(book)).length)
const readingBooks = computed(() => books.value.filter((book) => !isFinished(book)).length)

const categoryOptions = computed(() => {
  const categories = books.value
    .map((book) => book.spec.category || '未分类')
    .filter((category, index, arr) => arr.indexOf(category) === index)
    .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  return ['all', ...categories]
})

const filteredBooks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return books.value.filter((book) => {
    const category = book.spec.category || '未分类'
    const matchesQuery = !query
      || book.spec.title.toLowerCase().includes(query)
      || (book.spec.author || '').toLowerCase().includes(query)
    const matchesCategory = categoryFilter.value === 'all' || categoryFilter.value === category
    const matchesVisibility = visibilityFilter.value === 'all'
      || (visibilityFilter.value === 'visible' && !book.spec.hidden)
      || (visibilityFilter.value === 'hidden' && book.spec.hidden)
    const matchesReadState = readStateFilter.value === 'all'
      || (readStateFilter.value === 'reading' && !isFinished(book))
      || (readStateFilter.value === 'finished' && isFinished(book))
    return matchesQuery && matchesCategory && matchesVisibility && matchesReadState
  })
})

const rangeOptions: RangeOption[] = [
  { value: 'week', label: '周' },
  { value: 'month', label: '月' },
  { value: 'year', label: '年' },
  { value: 'all', label: '全部' }
]

const now = () => new Date()

const normalizeNumber = (value: number | undefined | null) => {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

const startOfDay = (date: Date) => {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  return d
}

const addDays = (date: Date, days: number) => {
  const d = new Date(date)
  d.setDate(d.getDate() + days)
  return d
}

const addMonths = (date: Date, months: number) => {
  const d = new Date(date)
  d.setMonth(d.getMonth() + months)
  return d
}

const formatDateKey = (date: Date) => {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const formatMonthKey = (date: Date) => {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

const getBookActivityTime = (book: BookItem) => {
  return normalizeNumber(book.spec.lastReadTime) || normalizeNumber(book.spec.finishTime)
}

const isFinished = (book: BookItem) => {
  return normalizeNumber(book.spec.finishTime) > 0 || normalizeNumber(book.spec.progress) >= 99
}

const libraryYears = computed(() => {
  const years = books.value
    .map((book) => getBookActivityTime(book))
    .filter((time) => time > 0)
    .map((time) => new Date(time).getFullYear())
  if (years.length === 0) return 0
  return Math.max(...years) - Math.min(...years) + 1
})

const statsYears = computed(() => {
  const currentYear = new Date().getFullYear()
  const years = books.value
    .map((book) => getBookActivityTime(book))
    .filter((time) => time > 0)
    .map((time) => new Date(time).getFullYear())
  for (let year = currentYear - 5; year <= currentYear; year++) {
    years.push(year)
  }
  return Array.from(new Set(years)).sort((a, b) => a - b)
})

const minStatsYear = computed(() => statsYears.value[0] || new Date().getFullYear())
const maxStatsYear = computed(() => Math.max(statsYears.value[statsYears.value.length - 1] || new Date().getFullYear(), new Date().getFullYear()))
const canGoPreviousYear = computed(() => selectedStatsYear.value > minStatsYear.value)
const canGoNextYear = computed(() => selectedStatsYear.value < maxStatsYear.value)

const changeStatsYear = (offset: number) => {
  selectedStatsYear.value = Math.min(maxStatsYear.value, Math.max(minStatsYear.value, selectedStatsYear.value + offset))
}

const latestActivityTime = computed(() => {
  return Math.max(...books.value.map((book) => getBookActivityTime(book)), 0)
})

const recentActiveBooks = computed(() => {
  const since = Date.now() - 7 * 24 * 60 * 60 * 1000
  return books.value.filter((book) => getBookActivityTime(book) >= since).length
})

const formatRelativeTime = (timestamp: number) => {
  if (!timestamp) return '暂无'
  const diff = Math.max(0, Date.now() - timestamp)
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}天前`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months}个月前`
  return `${Math.floor(months / 12)}年前`
}

const rangeStart = (range: StatsRange, base = now()) => {
  const today = startOfDay(base)
  if (range === 'week') {
    const day = today.getDay() || 7
    return addDays(today, 1 - day)
  }
  if (range === 'month') {
    return new Date(today.getFullYear(), today.getMonth(), 1)
  }
  if (range === 'year') {
    return new Date(selectedStatsYear.value, 0, 1)
  }
  return null
}

const rangeEnd = (range: StatsRange, base = now()) => {
  const start = rangeStart(range, base)
  if (!start) return null
  if (range === 'week') return addDays(start, 7)
  if (range === 'month') return addMonths(start, 1)
  if (range === 'year') return new Date(selectedStatsYear.value + 1, 0, 1)
  return null
}

const isInRange = (book: BookItem, range: StatsRange, start: Date | null, end: Date | null) => {
  if (range === 'all') return true
  const activityTime = getBookActivityTime(book)
  return activityTime >= (start?.getTime() || 0) && activityTime < (end?.getTime() || Number.MAX_SAFE_INTEGER)
}

const activeBooks = computed(() => {
  const start = rangeStart(statsRange.value)
  const end = rangeEnd(statsRange.value)
  return books.value.filter((book) => isInRange(book, statsRange.value, start, end))
})

const previousBooks = computed(() => {
  if (statsRange.value === 'all') return []
  const currentStart = rangeStart(statsRange.value)
  const currentEnd = rangeEnd(statsRange.value)
  if (!currentStart || !currentEnd) return []
  const span = currentEnd.getTime() - currentStart.getTime()
  const previousStart = new Date(currentStart.getTime() - span)
  const previousEnd = new Date(currentStart.getTime())
  return books.value.filter((book) => isInRange(book, statsRange.value, previousStart, previousEnd))
})

const activeReadingMinutes = computed(() => activeBooks.value.reduce((s, b) => s + normalizeNumber(b.spec.readingTime), 0))
const secondsToMinutes = (seconds: number | undefined | null) => Math.round(normalizeNumber(seconds) / 60)
const usingOfficialStats = computed(() => officialStats.value !== null)
const displayReadingMinutes = computed(() => {
  return usingOfficialStats.value ? secondsToMinutes(officialStats.value?.totalReadTime) : activeReadingMinutes.value
})

const activeReadDays = computed(() => {
  const days = new Set<string>()
  activeBooks.value.forEach((book) => {
    const activityTime = getBookActivityTime(book)
    if (activityTime > 0) {
      days.add(formatDateKey(new Date(activityTime)))
    }
  })
  return days.size
})

const previousReadingMinutes = computed(() => previousBooks.value.reduce((s, b) => s + normalizeNumber(b.spec.readingTime), 0))

const displayAverageMinutes = computed(() => {
  if (usingOfficialStats.value) return secondsToMinutes(officialStats.value?.dayAverageReadTime)
  return activeReadDays.value > 0 ? Math.round(activeReadingMinutes.value / activeReadDays.value) : 0
})

const splitCountUnit = (value: string, fallbackUnit: string) => {
  const normalized = value.trim()
  if (!normalized) return { value: '0', unit: fallbackUnit }
  const match = normalized.match(/^(\d+(?:\.\d+)?)(.*)$/)
  if (!match) return { value: normalized, unit: '' }
  return {
    value: match[1],
    unit: match[2].trim() || fallbackUnit
  }
}

const rangeLabel = computed(() => {
  if (statsRange.value === 'year') return `${selectedStatsYear.value}年`
  return rangeOptions.find((item) => item.value === statsRange.value)?.label || '月'
})

const chartTitle = computed(() => {
  if (statsRange.value === 'year') return statsChartView.value === 'heatmap' ? '每日阅读时长' : '每月阅读时长'
  return statsRange.value === 'all' ? '每月阅读时长' : '每日阅读时长'
})

const formatDurationParts = (minutes: number) => {
  const normalized = Math.max(0, Math.round(minutes))
  return {
    hours: Math.floor(normalized / 60),
    minutes: normalized % 60
  }
}

const displayDurationParts = computed(() => formatDurationParts(displayReadingMinutes.value))

const compareText = computed(() => {
  if (usingOfficialStats.value && officialStats.value?.compare !== undefined) {
    const compare = officialStats.value.compare
    const percent = Math.round(Math.abs(compare) * 100)
    return {
      text: `${compare >= 0 ? '较上期 +' : '较上期 -'}${percent}%`,
      up: compare >= 0
    }
  }
  if (statsRange.value === 'all' || previousReadingMinutes.value <= 0) return undefined
  const diff = activeReadingMinutes.value - previousReadingMinutes.value
  const percent = Math.round(Math.abs(diff) / previousReadingMinutes.value * 100)
  return {
    text: `${diff >= 0 ? '较上期 +' : '较上期 -'}${percent}%`,
    up: diff >= 0
  }
})

const statCards = computed<StatCardItem[]>(() => {
  const readDays = usingOfficialStats.value ? normalizeNumber(officialStats.value?.readDays) : activeReadDays.value
  const readCount = officialStats.value?.readStat?.find((item) => item.stat === '读过')?.counts || String(activeBooks.value.length)
  const finishCount = officialStats.value?.readStat?.find((item) => item.stat === '读完')?.counts || String(activeBooks.value.filter((book) => isFinished(book)).length)
  const noteCount = officialStats.value?.readStat?.find((item) => item.stat === '笔记')?.counts
    || String(activeBooks.value.reduce((s, b) => s + normalizeNumber(b.spec.noteCount) + normalizeNumber(b.spec.reviewCount), 0))
  const readCountParts = splitCountUnit(readCount, '本')
  const finishCountParts = splitCountUnit(finishCount, '本')
  const noteCountParts = splitCountUnit(noteCount, '条')
  return [
    { label: '阅读天数', value: String(readDays), unit: '天', icon: 'calendar' },
    { label: '日均时长', value: formatDuration(displayAverageMinutes.value), unit: '', icon: 'trend', compare: compareText.value },
    { label: '读过', value: readCountParts.value, unit: readCountParts.unit, icon: 'book' },
    { label: '读完', value: finishCountParts.value, unit: finishCountParts.unit, icon: 'check' },
    { label: '笔记', value: noteCountParts.value, unit: noteCountParts.unit, icon: 'pen' }
  ]
})

const chartPoints = computed<ChartPoint[]>(() => {
  if (usingOfficialStats.value && officialStats.value?.readTimes) {
    if (statsRange.value === 'year') {
      const values = new Map<string, number>()
      Object.entries(officialStats.value.readTimes).forEach(([key, value]) => {
        const timestamp = Number(key)
        if (timestamp >= 0 && timestamp <= 12) {
          const month = timestamp === 0 ? 0 : timestamp - 1
          const monthKey = formatMonthKey(new Date(selectedStatsYear.value, month, 1))
          values.set(monthKey, (values.get(monthKey) || 0) + secondsToMinutes(value))
          return
        }
        const date = new Date(timestamp > 10_000_000_000 ? timestamp : timestamp * 1000)
        if (date.getFullYear() !== selectedStatsYear.value) return
        const monthKey = formatMonthKey(date)
        values.set(monthKey, (values.get(monthKey) || 0) + secondsToMinutes(value))
      })
      return Array.from({ length: 12 }, (_, index) => {
        const date = new Date(selectedStatsYear.value, index, 1)
        const key = formatMonthKey(date)
        return {
          key,
          label: `${index + 1}月`,
          value: values.get(key) || 0,
          future: date.getTime() > now().getTime()
        }
      })
    }
    return Object.entries(officialStats.value.readTimes)
      .sort(([a], [b]) => Number(a) - Number(b))
      .map(([key, value]) => {
        const timestamp = Number(key)
        const date = new Date(timestamp > 10_000_000_000 ? timestamp : timestamp * 1000)
        const label = statsRange.value === 'all' ? `${date.getFullYear()}/${date.getMonth() + 1}` : `${date.getMonth() + 1}/${date.getDate()}`
        return { key, label, value: secondsToMinutes(value) }
      })
  }
  const current = now()
  const values = new Map<string, number>()
  activeBooks.value.forEach((book) => {
    const activityTime = getBookActivityTime(book)
    if (activityTime <= 0) return
    const date = new Date(activityTime)
    const key = statsRange.value === 'year' || statsRange.value === 'all'
      ? formatMonthKey(date)
      : formatDateKey(date)
    values.set(key, (values.get(key) || 0) + normalizeNumber(book.spec.readingTime))
  })

  if (statsRange.value === 'week') {
    const start = rangeStart('week', current) || startOfDay(current)
    return Array.from({ length: 7 }, (_, index) => {
      const date = addDays(start, index)
      const key = formatDateKey(date)
      return { key, label: `${date.getMonth() + 1}/${date.getDate()}`, value: values.get(key) || 0 }
    })
  }

  if (statsRange.value === 'month') {
    const start = rangeStart('month', current) || new Date(current.getFullYear(), current.getMonth(), 1)
    const days = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate()
    return Array.from({ length: days }, (_, index) => {
      const date = new Date(start.getFullYear(), start.getMonth(), index + 1)
      const key = formatDateKey(date)
      return {
        key,
        label: String(index + 1),
        value: values.get(key) || 0,
        future: date.getTime() > current.getTime()
      }
    })
  }

  if (statsRange.value === 'year') {
    return Array.from({ length: 12 }, (_, index) => {
      const date = new Date(selectedStatsYear.value, index, 1)
      const key = formatMonthKey(date)
      return { key, label: `${index + 1}月`, value: values.get(key) || 0, future: date.getTime() > current.getTime() }
    })
  }

  const years = books.value
    .map((book) => getBookActivityTime(book))
    .filter((time) => time > 0)
    .map((time) => new Date(time).getFullYear())
  const minYear = years.length > 0 ? Math.min(...years) : current.getFullYear()
  const maxYear = years.length > 0 ? Math.max(...years) : current.getFullYear()
  const points: ChartPoint[] = []
  for (let year = minYear; year <= maxYear; year++) {
    for (let month = 0; month < 12; month++) {
      const date = new Date(year, month, 1)
      const key = formatMonthKey(date)
      points.push({ key, label: `${String(year).slice(2)}/${month + 1}`, value: values.get(key) || 0 })
    }
  }
  return points
})

const maxChartValue = computed(() => Math.max(...chartPoints.value.map((point) => point.value), 1))

const heatmapWeeks = computed<HeatmapWeek[]>(() => {
  const today = startOfDay(now())
  const start = statsRange.value === 'all'
    ? addDays(today, -364)
    : rangeStart(statsRange.value, today) || addDays(today, -364)
  const end = statsRange.value === 'all'
    ? addDays(today, 1)
    : rangeEnd(statsRange.value, today) || addDays(today, 1)
  const values = new Map<string, number>()
  if (usingOfficialStats.value && officialStats.value?.readTimes) {
    Object.entries(officialStats.value.readTimes).forEach(([key, value]) => {
      const timestamp = Number(key)
      if (timestamp >= 0 && timestamp <= 12) return
      const date = new Date(timestamp > 10_000_000_000 ? timestamp : timestamp * 1000)
      if (date < start || date >= end) return
      const dateKey = formatDateKey(date)
      values.set(dateKey, (values.get(dateKey) || 0) + secondsToMinutes(value))
    })
  }
  if (values.size === 0) {
    activeBooks.value.forEach((book) => {
      const activityTime = getBookActivityTime(book)
      if (activityTime <= 0) return
      const key = formatDateKey(new Date(activityTime))
      values.set(key, (values.get(key) || 0) + normalizeNumber(book.spec.readingTime))
    })
  }

  const cells: HeatmapCell[] = []
  const leading = (start.getDay() + 6) % 7
  for (let index = 0; index < leading; index++) {
    cells.push({ key: `empty-start-${index}`, label: '', value: 0, level: 0, empty: true })
  }

  const maxValue = Math.max(...Array.from(values.values()), 1)
  for (let date = new Date(start); date < end; date = addDays(date, 1)) {
    const key = formatDateKey(date)
    const value = values.get(key) || 0
    const level = value === 0 ? 0 : Math.min(4, Math.ceil(value / maxValue * 4))
    cells.push({
      key,
      label: `${key} ${formatDuration(value)}`,
      value,
      level,
      empty: false
    })
  }

  while (cells.length % 7 !== 0) {
    cells.push({ key: `empty-end-${cells.length}`, label: '', value: 0, level: 0, empty: true })
  }

  const weeks: HeatmapWeek[] = []
  for (let index = 0; index < cells.length; index += 7) {
    weeks.push({ key: `week-${index / 7}`, cells: cells.slice(index, index + 7) })
  }
  return weeks
})

const heatmapMonthLabels = computed<HeatmapMonthLabel[]>(() => {
  const start = rangeStart('year') || new Date(selectedStatsYear.value, 0, 1)
  const leading = (start.getDay() + 6) % 7
  return Array.from({ length: 12 }, (_, month) => {
    const date = new Date(selectedStatsYear.value, month, 1)
    const dayOffset = Math.floor((startOfDay(date).getTime() - start.getTime()) / 86_400_000)
    return {
      month: month + 1,
      column: Math.floor((leading + dayOffset) / 7) + 1
    }
  })
})

const topBooks = computed(() => {
  return [...activeBooks.value]
    .sort((a, b) => normalizeNumber(b.spec.readingTime) - normalizeNumber(a.spec.readingTime))
    .slice(0, 5)
})

const topReadingBooks = computed<TopReadingBook[]>(() => {
  if (usingOfficialStats.value && officialStats.value?.readLongest?.length) {
    return officialStats.value.readLongest.slice(0, 5).map((item, index) => {
      const book = item.book
      const album = item.albumInfo
      return {
        id: book?.bookId || album?.albumId || `official-${index}`,
        title: book?.title || album?.name || '未命名内容',
        author: book?.author || album?.authorName || '未知作者',
        category: item.tags?.join(' · ') || '微信读书',
        cover: book?.cover || album?.cover || '',
        readingMinutes: secondsToMinutes(item.readTime),
        progress: 100
      }
    })
  }
  return topBooks.value.map((book) => ({
    id: book.metadata.name,
    title: book.spec.title,
    author: book.spec.author || '未知作者',
    category: book.spec.category || '未分类',
    cover: book.spec.cover || '',
    readingMinutes: normalizeNumber(book.spec.readingTime),
    progress: normalizeNumber(book.spec.progress)
  }))
})

const categoryPreferences = computed<PreferenceItem[]>(() => {
  if (usingOfficialStats.value && officialStats.value?.preferCategory?.length) {
    const maxValue = Math.max(...officialStats.value.preferCategory.map((item) => item.readingTime), 1)
    return officialStats.value.preferCategory.slice(0, 6).map((item) => ({
      label: item.categoryTitle || item.parentCategoryTitle || '未分类',
      value: item.readingCount,
      percent: Math.round(item.readingTime / maxValue * 100)
    }))
  }
  const values = new Map<string, number>()
  activeBooks.value.forEach((book) => {
    const category = book.spec.category || '未分类'
    values.set(category, (values.get(category) || 0) + 1)
  })
  const maxValue = Math.max(...Array.from(values.values()), 1)
  return Array.from(values.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([label, value]) => ({ label, value, percent: Math.round(value / maxValue * 100) }))
})

const authorPreferences = computed<PreferenceItem[]>(() => {
  const values = new Map<string, number>()
  activeBooks.value.forEach((book) => {
    const author = book.spec.author || '未知作者'
    values.set(author, (values.get(author) || 0) + 1)
  })
  const maxValue = Math.max(...Array.from(values.values()), 1)
  return Array.from(values.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([label, value]) => ({ label, value, percent: Math.round(value / maxValue * 100) }))
})

const hourDistribution = computed<ChartPoint[]>(() => {
  if (usingOfficialStats.value && officialStats.value?.preferTime?.length) {
    return officialStats.value.preferTime.map((value, index) => {
      const hour = (index + 6) % 24
      const key = String(hour).padStart(2, '0')
      return { key, label: key, value: secondsToMinutes(value) }
    })
  }
  const values = new Map<string, number>()
  activeBooks.value.forEach((book) => {
    const activityTime = getBookActivityTime(book)
    if (activityTime <= 0) return
    const hour = new Date(activityTime).getHours()
    const key = String(hour).padStart(2, '0')
    values.set(key, (values.get(key) || 0) + normalizeNumber(book.spec.readingTime))
  })
  return Array.from({ length: 24 }, (_, hour) => {
    const key = String(hour).padStart(2, '0')
    return { key, label: key, value: values.get(key) || 0 }
  })
})

const maxHourValue = computed(() => Math.max(...hourDistribution.value.map((point) => point.value), 1))

const agentMode = (range: StatsRange) => {
  const map: Record<StatsRange, string> = {
    week: 'weekly',
    month: 'monthly',
    year: 'annually',
    all: 'overall'
  }
  return map[range]
}

const fetchOfficialReadingStats = async () => {
  statsLoading.value = true
  statsError.value = ''
  try {
    const params = new URLSearchParams({ mode: agentMode(statsRange.value) })
    if (statsRange.value === 'year') {
      const baseTime = Math.floor(new Date(selectedStatsYear.value, 6, 1, 12).getTime() / 1000)
      params.set('baseTime', String(baseTime))
    }
    const res = await fetch(`/api/admin/halo-weread-plugin/reading-stats?${params.toString()}`)
    if (res.ok) {
      officialStats.value = await res.json()
      return
    }
    const data = await res.json().catch(() => ({ message: '阅读统计接口不可用' }))
    officialStats.value = null
    statsError.value = data.message || '阅读统计接口不可用'
  } catch (error) {
    officialStats.value = null
    statsError.value = error instanceof Error ? error.message : String(error)
  } finally {
    statsLoading.value = false
  }
}

const toggleReadingStats = () => {
  showReadingStats.value = !showReadingStats.value
  if (showReadingStats.value && !officialStats.value && !statsLoading.value) {
    fetchOfficialReadingStats()
  }
}

const fetchBooks = async () => {
  loading.value = true
  message.value = ''
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/books')
    if (res.ok) {
      books.value = await res.json()
      if (showReadingStats.value) {
        fetchOfficialReadingStats()
      }
    } else {
      message.value = '获取书籍列表失败'
    }
  } catch (error) {
    message.value = '请求失败'
  } finally {
    loading.value = false
  }
}

const deleteBook = async (name: string, title: string) => {
  if (!confirm('确定删除「' + title + '」？')) return
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/books/' + name, { method: 'DELETE' })
    if (res.ok) {
      books.value = books.value.filter((b) => b.metadata.name !== name)
      showToast('书籍已成功删除')
    } else {
      const err = await res.json()
      showToast(err.message || '删除失败', 'error')
    }
  } catch (error) {
    showToast('删除出现异常', 'error')
  }
}

const toggleVisibility = async (book: BookItem) => {
  try {
    const res = await fetch(`/api/admin/halo-weread-plugin/books/${book.metadata.name}/toggle-visibility`, {
      method: 'PATCH'
    })
    if (res.ok) {
      const data = await res.json()
      book.spec.hidden = data.hidden
      showToast(book.spec.hidden ? '书籍已设置为隐藏' : '书籍已设置为显示')
    } else {
      showToast('状态更新失败', 'error')
    }
  } catch (error) {
    showToast('操作异常', 'error')
  }
}

// 打开书籍详情抽屉
const openDrawer = async (book: BookItem) => {
  drawerBook.value = book
  drawerNotes.value = null
  notesError.value = ''
  drawerOpen.value = true
  notesLoading.value = true

  try {
    const res = await fetch(`/api/admin/halo-weread-plugin/books/${book.spec.bookId}/notes`)
    if (res.ok) {
      drawerNotes.value = await res.json()
    } else {
      notesError.value = '加载笔记失败'
    }
  } catch (e) {
    notesError.value = '请求异常'
  } finally {
    notesLoading.value = false
  }
}

const closeDrawer = () => {
  drawerOpen.value = false
  setTimeout(() => {
    drawerBook.value = null
    drawerNotes.value = null
  }, 300)
}

// 划线颜色映射（微信读书 style 字段）
const bookmarkColorClass = (colorStyle: number) => {
  const map: Record<number, string> = {
    1: 'bm-yellow',
    2: 'bm-red',
    3: 'bm-blue',
    4: 'bm-purple'
  }
  return map[colorStyle] || 'bm-yellow'
}

const formatTime = (ts: number) => {
  if (!ts) return '--'
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const formatDate = (ts: number) => {
  if (!ts) return '--'
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const formatDuration = (minutes: number) => {
  if (!minutes) return '0分'
  if (minutes < 60) return minutes + '分'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return h > 0 ? `${h}时${m}分` : `${m}分`
}

const message = ref('')

onMounted(() => {
  fetchBooks()
})

watch(statsRange, () => {
  if (showReadingStats.value) {
    fetchOfficialReadingStats()
  }
})

watch(selectedStatsYear, () => {
  if (showReadingStats.value && statsRange.value === 'year') {
    fetchOfficialReadingStats()
  }
})
</script>

<template>
  <div class="bl-container">
    <!-- Toast 提示 -->
    <Transition name="toast">
      <div v-if="toast.show" class="h-toast" :class="toast.type">
        <span class="toast-icon">{{ toast.type === 'success' ? '✅' : '❌' }}</span>
        <span class="toast-text">{{ toast.message }}</span>
      </div>
    </Transition>

    <section class="book-toolbar">
      <div class="toolbar-row">
        <input
          v-model="searchQuery"
          class="book-search"
          type="search"
          placeholder="搜索书名或作者"
        />
        <div class="select-wrapper">
          <select v-model="categoryFilter" class="toolbar-select">
            <option value="all">全部类型</option>
            <option v-for="category in categoryOptions.filter((item) => item !== 'all')" :key="category" :value="category">
              {{ category }}
            </option>
          </select>
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="select-chevron"><path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/></svg>
        </div>
        <div class="select-wrapper">
          <select v-model="visibilityFilter" class="toolbar-select">
            <option value="all">全部状态</option>
            <option value="visible">显示中</option>
            <option value="hidden">已隐藏</option>
          </select>
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="select-chevron"><path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/></svg>
        </div>
        <div class="select-wrapper">
          <select v-model="readStateFilter" class="toolbar-select">
            <option value="all">全部书籍</option>
            <option value="reading">在读</option>
            <option value="finished">已读</option>
          </select>
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="select-chevron"><path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/></svg>
        </div>
        <div class="toolbar-round-actions">
          <button
            class="view-toggle-btn"
            :class="{ active: showReadingStats }"
            title="阅读统计"
            type="button"
            @click="!showReadingStats && toggleReadingStats()"
          >
            <svg class="view-toggle-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="4" y="4" width="5" height="5" rx="1.4" />
              <rect x="9.5" y="4" width="5" height="5" rx="1.4" />
              <rect x="15" y="4" width="5" height="5" rx="1.4" />
              <rect x="4" y="9.5" width="5" height="5" rx="1.4" />
              <rect x="9.5" y="9.5" width="5" height="5" rx="1.4" />
              <rect x="15" y="9.5" width="5" height="5" rx="1.4" />
              <rect x="4" y="15" width="5" height="5" rx="1.4" />
              <rect x="9.5" y="15" width="5" height="5" rx="1.4" />
              <rect x="15" y="15" width="5" height="5" rx="1.4" />
            </svg>
          </button>
          <button
            class="view-toggle-btn"
            :class="{ active: !showReadingStats }"
            title="书籍列表"
            type="button"
            @click="showReadingStats && toggleReadingStats()"
          >
            <svg class="view-toggle-icon list-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="5.5" cy="6.5" r="1.5" />
              <circle cx="5.5" cy="12" r="1.5" />
              <circle cx="5.5" cy="17.5" r="1.5" />
              <rect x="9" y="5.25" width="11" height="2.5" rx="1.25" />
              <rect x="9" y="10.75" width="11" height="2.5" rx="1.25" />
              <rect x="9" y="16.25" width="11" height="2.5" rx="1.25" />
            </svg>
          </button>
        </div>
      </div>
    </section>

    <!-- 阅读统计面板 -->
    <section v-if="showReadingStats" class="reading-panel">
      <div class="stats-toolbar">
        <div class="toolbar-actions">
          <div class="range-tabs">
            <button
              v-for="item in rangeOptions"
              :key="item.value"
              class="range-tab"
              :class="{ active: statsRange === item.value }"
              @click="statsRange = item.value"
            >
              {{ item.label }}
            </button>
          </div>
          <div v-if="statsRange === 'year'" class="year-stepper">
            <button type="button" :disabled="!canGoPreviousYear" @click="changeStatsYear(-1)">‹</button>
            <strong>{{ selectedStatsYear }}年</strong>
            <button type="button" :disabled="!canGoNextYear" @click="changeStatsYear(1)">›</button>
          </div>
        </div>
      </div>

      <div class="reading-hero">
        <div class="hero-main">
          <div class="hero-value stats-duration">
            <strong>{{ displayDurationParts.hours }}</strong><span>小时</span>
            <strong>{{ displayDurationParts.minutes }}</strong><span>分钟</span>
          </div>
          <div class="hero-sub">
            <span v-if="statsLoading">正在获取微信读书官方统计...</span>
            <span v-else-if="statsError">官方统计不可用，已回退本地数据：{{ statsError }}</span>
            <span v-else>
              日均阅读 {{ formatDuration(displayAverageMinutes) }}
              <b v-if="compareText" :class="compareText.up ? 'up' : 'down'"> · {{ compareText.text }}</b>
            </span>
          </div>
        </div>
      </div>
      <div class="stat-card-grid">
        <div v-for="item in statCards" :key="item.label" class="stat-card">
          <span class="stat-icon" :class="item.icon"></span>
          <div class="kpi-value">
            {{ item.value }}
            <span v-if="item.unit">{{ item.unit }}</span>
          </div>
          <div class="stat-label">
            <span>{{ item.label }}</span>
            <b v-if="item.compare" :class="item.compare.up ? 'up' : 'down'">{{ item.compare.text.replace('较上期 ', '') }}</b>
          </div>
        </div>
      </div>

      <div class="stats-layout stats-layout-single">
        <div class="stats-section time-chart-section year-style">
          <div class="section-head">
            <h3>{{ chartTitle }}</h3>
            <div v-if="statsRange === 'year'" class="chart-switch">
              <button
                type="button"
                :class="{ active: statsChartView === 'heatmap' }"
                title="热力图"
                @click="statsChartView = 'heatmap'"
              >
                ▦
              </button>
              <button
                type="button"
                :class="{ active: statsChartView === 'bar' }"
                title="柱状图"
                @click="statsChartView = 'bar'"
              >
                ▥
              </button>
            </div>
          </div>
          <div v-if="statsRange === 'year' && statsChartView === 'heatmap'" class="year-heatmap">
            <div class="annual-heatmap-scroll">
              <div
                class="month-axis"
                :style="{ gridTemplateColumns: `repeat(${heatmapWeeks.length}, minmax(10px, 1fr))` }"
              >
                <span
                  v-for="label in heatmapMonthLabels"
                  :key="label.month"
                  :style="{ gridColumn: String(label.column) }"
                >
                  {{ label.month }}月
                </span>
              </div>
              <div class="heatmap-wrap annual" :style="{ '--heatmap-week-count': heatmapWeeks.length }">
                <div v-for="week in heatmapWeeks" :key="week.key" class="heatmap-week">
                  <div
                    v-for="cell in week.cells"
                    :key="cell.key"
                    class="heatmap-cell"
                    :class="[`level-${cell.level}`, { empty: cell.empty }]"
                    :title="cell.label"
                  ></div>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="bar-chart" :class="{ dense: chartPoints.length > 18, 'annual-bars': statsRange === 'year' }">
            <div
              v-for="point in chartPoints"
              :key="point.key"
              class="bar-item"
              :class="{ future: point.future }"
              :title="`${point.key} · ${formatDuration(point.value)}`"
            >
              <div class="bar-track">
                <div class="bar-fill" :style="{ height: Math.max(4, Math.round(point.value / maxChartValue * 100)) + '%' }"></div>
              </div>
              <span>{{ point.label }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="stats-layout lower">
        <div class="stats-section top-books-section">
          <div class="section-head">
            <h3>阅读时长 Top {{ topReadingBooks.length }}</h3>
            <span>{{ rangeLabel }}</span>
          </div>
          <div v-if="topReadingBooks.length === 0" class="stats-empty">暂无当前范围内的阅读记录</div>
          <div v-else class="top-book-list">
            <div v-for="(book, index) in topReadingBooks" :key="book.id" class="top-book">
              <div class="top-rank">{{ index + 1 }}</div>
              <img v-if="book.cover" :src="book.cover" class="top-cover" />
              <div v-else class="top-cover placeholder"></div>
              <div class="top-book-info">
                <div class="top-title">{{ book.title }}</div>
                <div class="top-meta">{{ book.author }} · {{ book.category }}</div>
                <div class="mini-progress">
                  <span :style="{ width: Math.min(book.progress || 0, 100) + '%' }"></span>
                </div>
              </div>
              <div class="top-duration">{{ formatDuration(book.readingMinutes) }}</div>
            </div>
          </div>
        </div>

        <div class="stats-section preference-section">
          <div class="section-head">
            <h3>偏好分析</h3>
            <span>分类 / 作者 / 时段</span>
          </div>
          <div class="preference-block">
            <div class="preference-title">分类偏好</div>
            <div v-for="item in categoryPreferences" :key="item.label" class="preference-row">
              <span>{{ item.label }}</span>
              <div class="preference-bar"><i :style="{ width: item.percent + '%' }"></i></div>
              <em>{{ item.value }}</em>
            </div>
          </div>
          <div class="preference-block">
            <div class="preference-title">偏好作者</div>
            <div v-for="item in authorPreferences" :key="item.label" class="author-pill">
              <span>{{ item.label }}</span>
              <em>{{ item.value }}本</em>
            </div>
          </div>
          <div class="preference-block">
            <div class="preference-title">24 小时阅读时段</div>
            <div class="hour-chart">
              <div
                v-for="point in hourDistribution"
                :key="point.key"
                class="hour-bar"
                :title="`${point.label}:00 · ${formatDuration(point.value)}`"
              >
                <span :style="{ height: Math.max(3, Math.round(point.value / maxHourValue * 100)) + '%' }"></span>
              </div>
            </div>
            <div class="hour-axis">
              <span>0</span>
              <span>6</span>
              <span>12</span>
              <span>18</span>
              <span>23</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 列表区域 -->
    <div class="list-wrapper">
      <!-- 桌面端表格 -->
      <table class="h-table desktop-only">
        <thead>
          <tr>
            <th width="60">封面</th>
            <th>书名与作者</th>
            <th width="100">阅读进度</th>
            <th width="110">阅读时长</th>
            <th width="80" class="text-center">划线</th>
            <th width="80" class="text-center">想法</th>
            <th width="150">最后阅读</th>
            <th width="160" class="text-center">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="book in filteredBooks" :key="book.metadata.name" :class="{ 'row-hidden': book.spec.hidden }">
            <td>
              <div class="book-cover-wrapper">
                <img v-if="book.spec.cover" :src="book.spec.cover" class="book-cover" />
                <div v-else class="book-cover-placeholder"></div>
              </div>
            </td>
            <td>
              <div class="book-info">
                <a v-if="book.spec.pcUrl" :href="book.spec.pcUrl" target="_blank" class="book-title" :title="book.spec.title">
                  {{ book.spec.title }}
                </a>
                <div v-else class="book-title" :title="book.spec.title">{{ book.spec.title }}</div>
                <div class="book-author">{{ book.spec.author }}</div>
              </div>
            </td>
            <td>
              <div class="progress-col">
                <div class="progress-bar-bg">
                  <div class="progress-bar-fill" :style="{ width: Math.min(book.spec.progress || 0, 100) + '%' }"></div>
                </div>
                <span class="progress-text">{{ (book.spec.progress || 0).toFixed(1) }}%</span>
              </div>
            </td>
            <td class="text-muted fs-12">{{ formatDuration(book.spec.readingTime) }}</td>
            <td class="text-center fw-500">{{ book.spec.noteCount || 0 }}</td>
            <td class="text-center fw-500">{{ book.spec.reviewCount || 0 }}</td>
            <td class="text-muted fs-11">{{ formatDate(book.spec.lastReadTime) }}</td>
            <td class="text-center">
              <div class="row-actions">
                <button
                  v-if="(book.spec.noteCount || 0) + (book.spec.reviewCount || 0) > 0"
                  class="row-action-btn notes-btn"
                  @click="openDrawer(book)"
                  title="查看笔记"
                >
                  <svg class="row-action-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M7 2.75v3.5M17 2.75v3.5M11 2.75v3.5M6.75 5h10.5A3.75 3.75 0 0 1 21 8.75v9.5A3.75 3.75 0 0 1 17.25 22H6.75A3.75 3.75 0 0 1 3 18.25v-9.5A3.75 3.75 0 0 1 6.75 5Z" />
                    <path d="M7.5 10.5h9M7.5 15h5" />
                  </svg>
                  <span>笔记</span>
                </button>
                <div class="switch-wrapper row-visibility-action" :title="book.spec.hidden ? '当前已隐藏' : '当前已显示'">
                  <label class="h-switch">
                    <input type="checkbox" :checked="book.spec.hidden" @change="toggleVisibility(book)">
                    <span class="slider round"></span>
                  </label>
                  <span class="switch-label">{{ book.spec.hidden ? '隐藏' : '显示' }}</span>
                </div>
                <button class="row-action-btn danger" @click="deleteBook(book.metadata.name, book.spec.title)" title="删除书籍">
                  <svg class="row-action-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M4 7h16M10 11v6M14 11v6M9 7l.75-3h4.5L15 7M6.5 7l.75 13h9.5L17.5 7" />
                  </svg>
                  <span>删除</span>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 移动端卡片列表 -->
      <div class="mobile-only book-cards">
        <div v-for="book in filteredBooks" :key="book.metadata.name" class="book-card" :class="{ 'card-hidden': book.spec.hidden }">
          <div class="card-main">
            <div class="book-cover-wrapper">
              <img v-if="book.spec.cover" :src="book.spec.cover" class="book-cover" />
              <div v-else class="book-cover-placeholder"></div>
            </div>
            <div class="card-info">
              <div class="book-title">{{ book.spec.title }}</div>
              <div class="book-author">{{ book.spec.author }}</div>
              <div class="card-stats">
                <span>划线 {{ book.spec.noteCount || 0 }}</span>
                <span class="dot">·</span>
                <span class="status-tag" :class="book.spec.hidden ? 'tag-hidden' : 'tag-visible'">
                  {{ book.spec.hidden ? '隐藏' : '显示' }}
                </span>
              </div>
            </div>
            <div class="card-action-btns">
               <label class="h-switch mini">
                 <input type="checkbox" :checked="book.spec.hidden" @change="toggleVisibility(book)">
                 <span class="slider round"></span>
               </label>
               <button
                 v-if="(book.spec.noteCount || 0) + (book.spec.reviewCount || 0) > 0"
                 class="card-notes-btn"
                 @click="openDrawer(book)"
               >笔记</button>
               <button class="card-del-btn" @click="deleteBook(book.metadata.name, book.spec.title)">删除</button>
            </div>
          </div>
          <div class="card-footer">
            <div class="card-progress">
              <div class="progress-header">
                <span class="progress-label">阅读进度</span>
                <span class="progress-text">{{ (book.spec.progress || 0).toFixed(1) }}%</span>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" :style="{ width: Math.min(book.spec.progress || 0, 100) + '%' }"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 笔记详情侧边抽屉 -->
    <Transition name="drawer-mask">
      <div v-if="drawerOpen" class="drawer-mask" @click.self="closeDrawer"></div>
    </Transition>

    <Transition name="drawer">
      <div v-if="drawerOpen" class="notes-drawer">
        <!-- 抽屉头部 -->
        <div class="drawer-header">
          <div class="drawer-book-info" v-if="drawerBook">
            <img v-if="drawerBook.spec.cover" :src="drawerBook.spec.cover" class="drawer-cover" />
            <div>
              <div class="drawer-title">{{ drawerBook.spec.title }}</div>
              <div class="drawer-author">{{ drawerBook.spec.author }}</div>
              <div class="drawer-stats" v-if="drawerNotes">
                <span class="stat-badge blue">划线 {{ drawerNotes.totalBookmarks }}</span>
                <span class="stat-badge orange">想法 {{ drawerNotes.totalReviews }}</span>
              </div>
            </div>
          </div>
          <button class="drawer-close" @click="closeDrawer">✕</button>
        </div>

        <!-- 抽屉内容 -->
        <div class="drawer-body">
          <!-- 加载中 -->
          <div v-if="notesLoading" class="drawer-loading">
            <div class="loading-spinner"></div>
            <span>加载笔记中...</span>
          </div>

          <!-- 错误 -->
          <div v-else-if="notesError" class="drawer-error">{{ notesError }}</div>

          <!-- 无笔记 -->
          <div v-else-if="drawerNotes && drawerNotes.total === 0" class="drawer-empty">
            暂无已同步的笔记，请先触发同步
          </div>

          <!-- 笔记内容 -->
          <template v-else-if="drawerNotes">
            <!-- 书评 -->
            <div v-if="drawerNotes.bookReviews.length > 0" class="chapter-section">
              <div class="chapter-header book-review-header">
                <span class="chapter-icon">书评</span>
                <span class="chapter-title-text">全书书评</span>
              </div>
              <div v-for="br in drawerNotes.bookReviews" :key="br.markId" class="book-review-block">
                <p class="book-review-content">{{ br.content }}</p>
                <div class="note-time">{{ formatTime(br.createTime) }}</div>
              </div>
            </div>

            <!-- 按章节展示划线和想法 -->
            <div
              v-for="chapter in drawerNotes.chapters"
              :key="chapter.chapterUid"
              class="chapter-section"
            >
              <div class="chapter-header">
                <span class="chapter-icon">章</span>
                <span class="chapter-title-text">{{ chapter.chapterTitle || '未分章节' }}</span>
                <span class="chapter-count">
                  {{ chapter.bookmarks.length + chapter.reviews.length }}
                </span>
              </div>

              <!-- 划线 -->
              <div
                v-for="bm in chapter.bookmarks"
                :key="bm.markId"
                class="bookmark-block"
                :class="bookmarkColorClass(bm.colorStyle)"
              >
                <div class="bookmark-bar"></div>
                <div class="bookmark-content">{{ bm.content }}</div>
                <div class="note-time">{{ formatTime(bm.createTime) }}</div>
              </div>

              <!-- 想法（划线感想） -->
              <div
                v-for="rv in chapter.reviews"
                :key="rv.markId"
                class="review-block"
              >
                <div v-if="rv.abstractContent" class="review-abstract">
                  "{{ rv.abstractContent }}"
                </div>
                <div class="review-content">{{ rv.content }}</div>
                <div class="note-time">{{ formatTime(rv.createTime) }}</div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.bl-container {
  padding: 24px;
  animation: fadeIn 0.3s ease-out;
  max-width: 1480px;
  margin: 0 auto;
  background: #f8fafc;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Toast 样式 */
.h-toast {
  position: fixed;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 20px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
  min-width: 200px;
  border: 1px solid #eef0f2;
}

.toast-icon {
  font-size: 16px;
}

.toast-text {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

/* Toast 过渡动画 */
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -20px);
}

.book-toolbar {
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid #e4e9f1;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.05);
}

.toolbar-row {
  display: grid;
  grid-template-columns: minmax(200px, 1.5fr) repeat(3, minmax(108px, 0.45fr)) auto;
  align-items: center;
  gap: 4px;
}

.book-search,
.toolbar-select {
  width: 100%;
  height: 42px;
  border: 1px solid #d7dee8;
  border-radius: 7px;
  color: #172033;
  background: #fff;
  box-shadow: none;
  font-size: 1rem;
  font-weight: 700;
  box-sizing: border-box;
}

.book-search {
  padding: 0 10px;
}

.book-search::placeholder {
  color: #94a3b8;
}

.select-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.select-wrapper .toolbar-select {
  width: 100%;
  padding-right: 32px;
  appearance: none;
}

.select-chevron {
  position: absolute;
  right: 8px;
  pointer-events: none;
  color: #94a3b8;
}

.toolbar-select {
  padding: 0 22px 0 10px;
}

.toolbar-round-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  width: 142px;
  height: 42px;
  padding: 3px;
  border: 1px solid #dbe3ef;
  border-radius: 18px;
  background: #fbfdff;
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.05), 0 8px 20px rgba(15, 23, 42, 0.06);
}

.view-toggle-btn {
  width: 68px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 15px;
  color: #9aa6b8;
  background: transparent;
  cursor: pointer;
  transition: color 0.18s ease, background 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.view-toggle-btn:hover {
  color: #2563eb;
}

.view-toggle-btn.active {
  color: #2563eb;
  border-color: #bfdbfe;
  background: linear-gradient(180deg, #f8fbff 0%, #edf5ff 100%);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.16);
}

.view-toggle-icon {
  width: 24px;
  height: 24px;
  display: block;
  fill: currentColor;
}

.view-toggle-icon.list-icon {
  width: 25px;
  height: 25px;
}

.toolbar-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  color: #334155;
  flex-wrap: wrap;
}

.summary-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 18px;
  font-size: 0.62rem;
  font-weight: 700;
}

.summary-item strong {
  color: #172033;
  font-size: 0.72rem;
  font-weight: 850;
}

.summary-icon {
  position: relative;
  width: 15px;
  height: 15px;
  display: inline-block;
  color: #64748b;
  flex-shrink: 0;
}

.summary-icon.book {
  border: 1px solid currentColor;
  border-radius: 3px;
}

.summary-icon.book::after {
  content: "";
  position: absolute;
  left: -1px;
  right: -1px;
  bottom: 3px;
  height: 1px;
  background: currentColor;
}

.summary-icon.note::before {
  content: "";
  position: absolute;
  left: 0;
  top: 10px;
  width: 15px;
  height: 2px;
  border-radius: 999px;
  background: currentColor;
  transform: rotate(-45deg);
}

.summary-icon.note::after {
  content: "";
  position: absolute;
  left: 9px;
  top: 1px;
  width: 5px;
  height: 11px;
  border: 1px solid currentColor;
  border-radius: 3px;
  transform: rotate(45deg);
}

.summary-icon.calendar {
  border: 1px solid currentColor;
  border-radius: 4px;
}

.summary-icon.calendar::before {
  content: "";
  position: absolute;
  left: 2px;
  right: 2px;
  top: 5px;
  height: 1px;
  background: currentColor;
}

.summary-icon.clock {
  border: 1px solid currentColor;
  border-radius: 50%;
}

.summary-icon.clock::before {
  content: "";
  position: absolute;
  left: 7px;
  top: 3px;
  width: 1px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
}

.summary-icon.clock::after {
  content: "";
  position: absolute;
  left: 7px;
  top: 8px;
  width: 5px;
  height: 1px;
  border-radius: 999px;
  background: currentColor;
}

.summary-icon.sync::before {
  content: "↻";
  position: absolute;
  inset: -1px 0 0;
  font-size: 0.82rem;
  line-height: 1;
  font-weight: 900;
}

.reading-panel {
  margin-bottom: 24px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  box-shadow: none;
}

.stats-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.reader-card {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex-shrink: 0;
}

.reader-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #dbeafe;
  color: #2563eb;
  font-size: 0.62rem;
  font-weight: 800;
  flex-shrink: 0;
}

.reader-clover {
  color: #22c55e;
  font-size: 0.68rem;
  line-height: 1;
}

.reader-name {
  color: #0f172a;
  font-size: 1rem;
  font-weight: 800;
}

.reader-desc {
  margin-top: 3px;
  color: #64748b;
  font-size: 0.78rem;
  line-height: 1.5;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex: 1;
  flex-shrink: 0;
  min-width: 0;
}

.range-tabs {
  display: flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #fff;
}

.range-tab {
  border: none;
  cursor: pointer;
  font-weight: 700;
  transition: all 0.2s;
}

.range-tab {
  min-width: 48px;
  height: 28px;
  padding: 0 12px;
  border-radius: 7px;
  color: #111827;
  background: transparent;
  font-size: 0.72rem;
  box-shadow: inset 0 0 0 1px #e5e7eb;
}

.range-tab.active {
  background: #eef5ff;
  color: #93c5fd;
  box-shadow: none;
}

.year-stepper {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 3px;
  border: 1px solid #eef2f7;
  border-radius: 9px;
  background: #fff;
}

.year-stepper button {
  width: 26px;
  height: 26px;
  border: 1px solid #e5e7eb;
  border-radius: 7px;
  color: #111827;
  background: #fff;
  cursor: pointer;
  font-size: 0.78rem;
  line-height: 1;
  box-shadow: none;
}

.year-stepper button:disabled {
  color: #cbd5e1;
  cursor: not-allowed;
  box-shadow: none;
}

.year-stepper strong {
  min-width: 62px;
  color: #111827;
  font-size: 0.82rem;
  text-align: center;
  font-weight: 850;
}

.reading-hero {
  display: block;
  padding: 16px 20px;
  border: 1px solid #edf1f7;
  border-radius: 12px;
  background: #fff;
}

.hero-value {
  margin-top: 0;
  color: #0f172a;
  line-height: 1.1;
  font-weight: 900;
}

.stats-duration {
  display: flex;
  align-items: baseline;
  gap: 5px;
}

.stats-duration strong {
  color: #18181b;
  font-size: 1.9rem;
  line-height: 1;
  font-weight: 900;
}

.stats-duration span {
  color: #18181b;
  font-size: 0.72rem;
  font-weight: 850;
}

.hero-sub {
  margin-top: 10px;
  color: #4b5563;
  font-size: 0.74rem;
  font-weight: 700;
}

.hero-sub b {
  font-weight: 850;
}

.hero-sub b.up {
  color: #16a34a;
}

.hero-sub b.down {
  color: #dc2626;
}

.hero-side {
  display: flex;
  align-items: stretch;
  gap: 10px;
}

.mini-stat {
  min-width: 64px;
  padding: 8px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid #dbeafe;
  text-align: center;
}

.mini-stat span {
  display: block;
  color: #0f172a;
  font-size: 0.95rem;
  font-weight: 900;
}

.mini-stat em {
  display: block;
  margin-top: 4px;
  color: #64748b;
  font-size: 0.62rem;
  font-style: normal;
  font-weight: 700;
}

.stat-card-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.stat-card {
  min-width: 0;
  min-height: 68px;
  display: grid;
  grid-template-columns: 18px 1fr;
  align-items: center;
  column-gap: 8px;
  padding: 12px;
  border: 1px solid #edf1f7;
  border-radius: 10px;
  background: #fff;
}

.stat-card .kpi-value {
  margin-top: 0;
  color: #18181b;
  font-size: 1.22rem;
  font-weight: 900;
}

.stat-card .kpi-value span {
  margin-left: 2px;
  color: #18181b;
  font-size: 0.68rem;
  font-weight: 850;
}

.stat-label {
  grid-column: 2;
  color: #4b5563;
  font-size: 0.68rem;
  font-weight: 800;
}

.stat-label b {
  display: inline-block;
  margin-left: 4px;
  padding: 1px 5px;
  border-radius: 5px;
  font-size: 0.62rem;
}

.stat-label b.up {
  color: #16a34a;
  background: #dcfce7;
}

.stat-label b.down {
  color: #dc2626;
  background: #fee2e2;
}

.stat-icon {
  position: relative;
  width: 15px;
  height: 15px;
  color: #bfdbfe;
}

.stat-icon.calendar {
  border: 1px solid currentColor;
  border-radius: 4px;
}

.stat-icon.calendar::before {
  content: "";
  position: absolute;
  left: 2px;
  right: 2px;
  top: 5px;
  height: 1px;
  background: currentColor;
}

.stat-icon.trend::before {
  content: "↗";
  position: absolute;
  inset: -5px 0 0;
  font-size: 1.15rem;
  font-weight: 500;
}

.stat-icon.book {
  border: 1px solid currentColor;
  border-radius: 3px;
}

.stat-icon.book::after {
  content: "";
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 50%;
  width: 1px;
  background: currentColor;
}

.stat-icon.check {
  border: 1px solid currentColor;
  border-radius: 50%;
}

.stat-icon.check::before {
  content: "";
  position: absolute;
  left: 4px;
  top: 6px;
  width: 7px;
  height: 4px;
  border-left: 1px solid currentColor;
  border-bottom: 1px solid currentColor;
  transform: rotate(-45deg);
}

.stat-icon.pen::before {
  content: "";
  position: absolute;
  left: 1px;
  top: 10px;
  width: 15px;
  height: 2px;
  border-radius: 999px;
  background: currentColor;
  transform: rotate(-45deg);
}

.stat-icon.pen::after {
  content: "";
  position: absolute;
  left: 10px;
  top: 1px;
  width: 4px;
  height: 11px;
  border: 1px solid currentColor;
  border-radius: 3px;
  transform: rotate(45deg);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
}

.kpi-card {
  min-width: 0;
  padding: 10px;
  border: 1px solid #eef2f7;
  border-radius: 14px;
  background: #fff;
}

.kpi-label {
  color: #64748b;
  font-size: 0.62rem;
  font-weight: 800;
}

.kpi-value {
  margin-top: 6px;
  color: #0f172a;
  font-size: 0.92rem;
  font-weight: 900;
  line-height: 1.15;
}

.kpi-value span {
  margin-left: 2px;
  color: #64748b;
  font-size: 0.6rem;
  font-weight: 700;
}

.kpi-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 8px;
  color: #94a3b8;
  font-size: 0.68rem;
  line-height: 1.4;
}

.kpi-footer b {
  padding: 2px 6px;
  border-radius: 999px;
  font-weight: 800;
  white-space: nowrap;
}

.kpi-footer b.up {
  color: #047857;
  background: #d1fae5;
}

.kpi-footer b.down {
  color: #b91c1c;
  background: #fee2e2;
}

.stats-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.85fr);
  gap: 14px;
  margin-top: 14px;
}

.stats-layout-single {
  grid-template-columns: 1fr;
}

.stats-layout.lower {
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.9fr);
}

.stats-section {
  min-width: 0;
  padding: 16px;
  border: 1px solid #eef2f7;
  border-radius: 14px;
  background: #fff;
}

.stats-section.year-style {
  margin-top: 18px;
  padding: 0;
  border: none;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.section-head h3 {
  margin: 0;
  color: #0f172a;
  font-size: 0.95rem;
  font-weight: 900;
}

.section-head span {
  color: #94a3b8;
  font-size: 0.72rem;
  font-weight: 700;
}

.chart-switch {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid #edf1f7;
  border-radius: 8px;
  background: #fff;
}

.chart-switch button {
  width: 24px;
  height: 24px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  color: #9ca3af;
  background: #fff;
  cursor: pointer;
  font-size: 0.7rem;
  font-weight: 900;
}

.chart-switch button.active {
  color: #93c5fd;
  background: #f8fbff;
}

.bar-chart {
  height: 160px;
  display: flex;
  align-items: end;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.annual-bars {
  height: 210px;
  padding: 16px 4px 6px;
}

.annual-bars .bar-track {
  max-width: 44px;
  height: 178px;
  border-radius: 7px 7px 0 0;
  background: transparent;
}

.annual-bars .bar-fill {
  border-radius: 7px 7px 0 0;
  background: #bfdbfe;
}

.bar-chart.dense {
  gap: 4px;
}

.bar-item {
  min-width: 22px;
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.bar-chart.dense .bar-item {
  min-width: 18px;
}

.bar-track {
  width: 100%;
  max-width: 18px;
  height: 128px;
  display: flex;
  align-items: flex-end;
  border-radius: 999px;
  background: #f1f5f9;
  overflow: hidden;
}

.bar-fill {
  width: 100%;
  min-height: 4px;
  border-radius: 999px;
  background: linear-gradient(180deg, #60a5fa 0%, #2563eb 100%);
}

.bar-item.future .bar-fill {
  opacity: 0.22;
}

.bar-item span {
  color: #94a3b8;
  font-size: 0.64rem;
  font-weight: 700;
}

.heatmap-wrap {
  display: flex;
  gap: 4px;
  max-width: 100%;
  overflow-x: auto;
  padding-bottom: 4px;
}

.year-heatmap {
  padding-top: 12px;
}

.annual-heatmap-scroll {
  max-width: 100%;
  overflow-x: auto;
  padding-bottom: 4px;
}

.month-axis {
  display: grid;
  gap: 3px;
  width: 100%;
  min-width: 700px;
  margin-bottom: 8px;
  color: #9ca3af;
  font-size: 0.7rem;
  font-weight: 800;
}

.month-axis span {
  white-space: nowrap;
}

.heatmap-wrap.annual {
  display: grid;
  grid-template-columns: repeat(var(--heatmap-week-count, 53), minmax(10px, 1fr));
  gap: 3px;
  width: 100%;
  min-width: 700px;
  overflow-x: visible;
  padding: 0 0 8px;
}

.heatmap-week {
  display: grid;
  grid-template-rows: repeat(7, minmax(10px, 1fr));
  gap: 3px;
}

.heatmap-cell {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 2px;
  background: #eef2f7;
}

.heatmap-cell.empty {
  visibility: hidden;
}

.heatmap-cell.level-1,
.heatmap-legend .level-1 {
  background: #bfdbfe;
}

.heatmap-cell.level-2,
.heatmap-legend .level-2 {
  background: #60a5fa;
}

.heatmap-cell.level-3,
.heatmap-legend .level-3 {
  background: #2563eb;
}

.heatmap-cell.level-4,
.heatmap-legend .level-4 {
  background: #1e3a8a;
}

.heatmap-legend {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 5px;
  margin-top: 10px;
  color: #94a3b8;
  font-size: 0.68rem;
  font-weight: 700;
}

.heatmap-legend i {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: #eef2f7;
}

.top-book-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.top-book {
  display: grid;
  grid-template-columns: 28px 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
}

.top-rank {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #2563eb;
  background: #dbeafe;
  font-size: 0.76rem;
  font-weight: 900;
}

.top-cover {
  width: 42px;
  height: 58px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 4px 10px rgba(15, 23, 42, 0.14);
}

.top-cover.placeholder {
  background: #f1f5f9;
}

.top-title {
  color: #0f172a;
  font-size: 0.86rem;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-meta {
  margin-top: 3px;
  color: #94a3b8;
  font-size: 0.72rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-progress {
  height: 5px;
  margin-top: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: #f1f5f9;
}

.mini-progress span {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #2563eb;
}

.top-duration {
  color: #334155;
  font-size: 0.78rem;
  font-weight: 800;
  white-space: nowrap;
}

.preference-block + .preference-block {
  margin-top: 14px;
}

.preference-title {
  margin-bottom: 8px;
  color: #475569;
  font-size: 0.82rem;
  font-weight: 900;
}

.preference-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}

.preference-row span,
.author-pill span {
  color: #334155;
  font-size: 0.82rem;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preference-row em,
.author-pill em {
  color: #94a3b8;
  font-size: 0.78rem;
  font-style: normal;
  font-weight: 800;
  text-align: right;
}

.preference-bar {
  height: 7px;
  border-radius: 999px;
  overflow: hidden;
  background: #f1f5f9;
}

.preference-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #60a5fa;
}

.author-pill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 9px;
  border-radius: 10px;
  background: #f8fafc;
  margin-bottom: 6px;
}

.hour-chart {
  height: 58px;
  display: grid;
  grid-template-columns: repeat(24, 1fr);
  gap: 3px;
  align-items: end;
}

.hour-bar {
  height: 100%;
  display: flex;
  align-items: flex-end;
  border-radius: 999px;
  background: #f8fafc;
  overflow: hidden;
}

.hour-bar span {
  display: block;
  width: 100%;
  border-radius: 999px;
  background: #2563eb;
}

.hour-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 5px;
  color: #94a3b8;
  font-size: 0.65rem;
  font-weight: 700;
}

.stats-empty {
  padding: 30px 0;
  color: #94a3b8;
  text-align: center;
  font-size: 0.82rem;
}

.list-wrapper {
  background: #fff;
  border: 1px solid #eef0f2;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
}

.h-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}

.h-table th {
  padding: 14px 20px;
  background: #f8fafc;
  font-size: 0.75rem;
  font-weight: 700;
  color: #475569;
  border-bottom: 1px solid #f1f5f9;
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.h-table td {
  padding: 16px 20px;
  border-bottom: 1px solid #f1f5f9;
  vertical-align: middle;
  font-size: 0.875rem;
  color: #334155;
}

.h-table tr:hover {
  background: #fdfdfd;
}

.book-cover-wrapper {
  width: 44px;
  height: 60px;
  flex-shrink: 0;
}

.book-cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
}

.book-cover-placeholder {
  width: 100%;
  height: 100%;
  background: #f1f5f9;
  border-radius: 6px;
}

.book-info {
  display: flex;
  flex-direction: column;
}

.book-title {
  font-weight: 700;
  color: #1e293b;
  max-width: 240px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-decoration: none;
  transition: color 0.2s;
  line-height: 1.4;
}

.book-title:hover {
  color: #3b82f6;
}

.book-author {
  font-size: 0.75rem;
  color: #64748b;
  margin-top: 4px;
}

.progress-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100px;
}

.progress-bar-bg {
  height: 6px;
  background: #f1f5f9;
  border-radius: 3px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
  border-radius: 3px;
  transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.progress-text {
  font-size: 0.75rem;
  color: #64748b;
  font-weight: 600;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 100px;
}

.row-action-btn {
  min-width: 50px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 0 6px;
  border: 1px solid #bfdbfe;
  border-radius: 9px;
  color: #2563eb;
  background: #f8fbff;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.08);
  cursor: pointer;
  font-size: 12px;
  font-weight: 800;
  line-height: 1;
  white-space: nowrap;
  flex-shrink: 0;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease, border-color 0.18s ease;
}

.row-action-btn:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  box-shadow: 0 10px 22px rgba(37, 99, 235, 0.14);
  transform: translateY(-1px);
}

.row-action-btn.danger {
  color: #ef4444;
  border-color: #fecaca;
  background: #fffafa;
  box-shadow: 0 8px 18px rgba(239, 68, 68, 0.08);
}

.row-action-btn.danger:hover {
  border-color: #fca5a5;
  background: #fef2f2;
  box-shadow: 0 10px 22px rgba(239, 68, 68, 0.14);
}

.row-action-icon {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.switch-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.switch-label {
  font-size: 12px;
  color: #1f2937;
  font-weight: 800;
  min-width: 24px;
}

.row-visibility-action {
  height: 26px;
  gap: 4px;
  padding: 0 6px;
  border: 1px solid #e5e7eb;
  border-radius: 9px;
  background: #f9fafb;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.06);
  flex-shrink: 0;
}

/* Switch 核心样式 */
.h-switch {
  position: relative;
  display: inline-block;
  width: 28px;
  height: 16px;
}

.h-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #d1d5db;
  transition: .3s;
}

.slider:before {
  position: absolute;
  content: "";
  height: 12px;
  width: 12px;
  left: 2px;
  bottom: 2px;
  background-color: white;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.18);
  transition: .3s;
}

input:checked + .slider {
  background-color: #60a5fa;
}

input:focus + .slider {
  box-shadow: 0 0 1px #60a5fa;
}

input:checked + .slider:before {
  transform: translateX(12px);
}

.slider.round {
  border-radius: 999px;
}

.slider.round:before {
  border-radius: 50%;
}

.h-switch.mini {
  width: 30px;
  height: 16px;
}

.h-switch.mini .slider:before {
  height: 10px;
  width: 10px;
  left: 3px;
  bottom: 3px;
}

.h-switch.mini input:checked + .slider:before {
  transform: translateX(14px);
}

.row-hidden {
  opacity: 0.6;
}

.status-tag {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 700;
}

.tag-visible {
  background: #f0fdf4;
  color: #16a34a;
}

.tag-hidden {
  background: #f1f5f9;
  color: #64748b;
}

.text-center {
  text-align: center;
}

.text-muted {
  color: #94a3b8;
}

.fs-12 {
  font-size: 0.75rem;
}

.fs-11 {
  font-size: 0.7rem;
}

.fw-500 {
  font-weight: 500;
}

/* 响应式样式 */
.mobile-only {
  display: none;
}

@media (max-width: 768px) {
  .bl-container {
    padding: 16px;
  }

  .book-toolbar {
    padding: 10px;
  }

  .toolbar-row {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .toolbar-round-actions {
    justify-content: center;
    width: 100%;
    height: 54px;
    padding: 4px;
    justify-self: center;
  }

  .view-toggle-btn {
    flex: 1;
    height: 44px;
  }

  .toolbar-summary {
    gap: 12px;
    margin-top: 10px;
  }

  .summary-item {
    font-size: 0.6rem;
  }

  .summary-item strong {
    font-size: 0.7rem;
  }

  .reading-panel {
    padding: 14px;
  }

  .stats-toolbar,
  .toolbar-actions,
  .reading-hero {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-actions {
    gap: 10px;
  }

  .range-tabs {
    width: 100%;
    justify-content: space-between;
  }

  .range-tab {
    flex: 1;
    height: 26px;
    padding: 0 8px;
    font-size: 0.68rem;
  }

  .year-stepper {
    justify-content: space-between;
  }

  .hero-value {
    font-size: 1.2rem;
  }

  .stats-duration strong {
    font-size: 1.8rem;
  }

  .stats-duration span {
    font-size: 0.72rem;
  }

  .hero-side {
    width: 100%;
  }

  .mini-stat {
    flex: 1;
  }

  .kpi-grid,
  .stat-card-grid,
  .stats-layout,
  .stats-layout.lower {
    grid-template-columns: 1fr;
  }

  .stat-card {
    min-height: 62px;
    padding: 11px;
  }

  .stat-card .kpi-value {
    font-size: 1.16rem;
  }

  .bar-chart {
    height: 140px;
  }

  .annual-bars {
    height: 180px;
  }

  .annual-bars .bar-track {
    height: 150px;
  }

  .bar-track {
    height: 104px;
  }

  .top-book {
    grid-template-columns: 24px 36px minmax(0, 1fr);
  }

  .top-duration {
    grid-column: 3;
  }

  .desktop-only {
    display: none;
  }

  .mobile-only {
    display: flex;
  }

  .list-wrapper {
    background: transparent;
    border: 0;
    border-radius: 0;
    overflow: visible;
    box-shadow: none;
  }

  .book-cards {
    flex-direction: column;
    gap: 14px;
  }

  .book-card {
    padding: 16px;
    border-radius: 16px;
    border: 1px solid #e5e7eb;
    background: #fff;
    box-shadow: 0 10px 26px rgba(15, 23, 42, 0.08);
    transition: all 0.2s;
  }

  .card-hidden {
    opacity: 0.8;
  }

  .card-main {
    display: flex;
    gap: 12px;
    position: relative;
    margin-bottom: 12px;
  }

  .card-info {
    flex: 1;
    min-width: 0;
  }

  .card-info .book-title {
    max-width: 100%;
    font-size: 0.95rem;
  }

  .card-stats {
    font-size: 0.75rem;
    color: #64748b;
    margin-top: 6px;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .dot {
    color: #e2e8f0;
  }

  .card-action-btns {
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: flex-end;
  }

  .card-notes-btn {
    padding: 4px 10px;
    border-radius: 6px;
    background: #eff6ff;
    border: 1px solid #bfdbfe;
    color: #3b82f6;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }

  .card-del-btn {
    padding: 4px 10px;
    border-radius: 6px;
    background: #fff;
    border: 1px solid #fee2e2;
    color: #ef4444;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }

  .card-footer {
    border-top: 1px solid #e5e7eb;
    padding-top: 18px;
  }

  .card-progress {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .progress-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .progress-label {
    color: #64748b;
    font-size: 0.92rem;
    font-weight: 700;
  }

  .card-progress .progress-text {
    color: #3b82f6;
    font-size: 1rem;
    font-weight: 800;
    line-height: 1;
  }

  .card-progress .progress-bar-bg {
    width: 100%;
    max-width: none;
    height: 7px;
    background: #eef2f7;
    border-radius: 999px;
  }

  .card-progress .progress-bar-fill {
    border-radius: 999px;
  }
}

/* =========================================
   笔记抽屉
   ========================================= */

.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.notes-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(520px, 100vw);
  background: #fff;
  z-index: 1001;
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 40px rgba(0, 0, 0, 0.12);
}

/* 抽屉头部 */
.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid #f1f5f9;
  background: #f8fafc;
  flex-shrink: 0;
}

.drawer-book-info {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.drawer-cover {
  width: 52px;
  height: 72px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  flex-shrink: 0;
}

.drawer-title {
  font-size: 1rem;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.4;
  max-width: 300px;
}

.drawer-author {
  font-size: 0.8rem;
  color: #64748b;
  margin-top: 4px;
}

.drawer-stats {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.stat-badge {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 100px;
}

.stat-badge.blue {
  background: #eff6ff;
  color: #3b82f6;
}

.stat-badge.orange {
  background: #fff7ed;
  color: #f97316;
}

.drawer-close {
  background: transparent;
  border: none;
  font-size: 18px;
  color: #94a3b8;
  cursor: pointer;
  padding: 4px;
  line-height: 1;
  transition: color 0.2s;
  flex-shrink: 0;
}

.drawer-close:hover {
  color: #1e293b;
}

/* 抽屉体 */
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

/* 加载状态 */
.drawer-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px 0;
  color: #64748b;
  font-size: 0.875rem;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid #e2e8f0;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.drawer-error,
.drawer-empty {
  text-align: center;
  padding: 60px 0;
  color: #94a3b8;
  font-size: 0.875rem;
}

.drawer-error {
  color: #ef4444;
}

/* 章节区块 */
.chapter-section {
  margin-bottom: 28px;
}

.chapter-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 2px solid #f1f5f9;
}

.chapter-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #3b82f6;
  color: #fff;
  font-size: 0.65rem;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.book-review-header .chapter-icon {
  background: #f97316;
}

.chapter-title-text {
  font-size: 0.875rem;
  font-weight: 700;
  color: #1e293b;
  flex: 1;
}

.chapter-count {
  font-size: 0.72rem;
  color: #94a3b8;
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 100px;
}

/* 划线块 */
.bookmark-block {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #fafafa;
}

.bookmark-bar {
  width: 4px;
  border-radius: 2px;
  flex-shrink: 0;
}

.bm-yellow .bookmark-bar { background: #fbbf24; }
.bm-red    .bookmark-bar { background: #ef4444; }
.bm-blue   .bookmark-bar { background: #3b82f6; }
.bm-purple .bookmark-bar { background: #a855f7; }

.bookmark-content {
  font-size: 0.875rem;
  color: #1e293b;
  line-height: 1.7;
  flex: 1;
}

/* 想法块 */
.review-block {
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: #fffbeb;
  border-left: 3px solid #fbbf24;
}

.review-abstract {
  font-size: 0.78rem;
  color: #92400e;
  font-style: italic;
  margin-bottom: 8px;
  line-height: 1.5;
  opacity: 0.8;
}

.review-content {
  font-size: 0.875rem;
  color: #1e293b;
  line-height: 1.7;
}

/* 书评块 */
.book-review-block {
  padding: 16px;
  border-radius: 8px;
  background: #fff7ed;
  margin-bottom: 12px;
  border: 1px solid #fed7aa;
}

.book-review-content {
  font-size: 0.875rem;
  color: #1e293b;
  line-height: 1.8;
  margin: 0 0 8px;
}

/* 笔记时间 */
.note-time {
  font-size: 0.7rem;
  color: #cbd5e1;
  margin-top: 6px;
  text-align: right;
}

/* 抽屉过渡动画 */
.drawer-mask-enter-active,
.drawer-mask-leave-active {
  transition: opacity 0.25s ease;
}
.drawer-mask-enter-from,
.drawer-mask-leave-to {
  opacity: 0;
}

.drawer-enter-active,
.drawer-leave-active {
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.drawer-enter-from,
.drawer-leave-to {
  transform: translateX(100%);
}
</style>
