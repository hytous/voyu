param(
    [string[]]$Pages = @(
        'China',
        'Beijing', 'Shanghai', 'Guangzhou', 'Shenzhen', 'Chengdu', 'Chongqing',
        "Xi'an", 'Hangzhou', 'Suzhou', 'Nanjing', 'Wuhan', 'Changsha', 'Xiamen',
        'Qingdao', 'Tianjin', 'Harbin', 'Shenyang', 'Dalian', 'Kunming', 'Lijiang',
        'Dali', 'Guilin', 'Yangshuo', 'Zhangjiajie', 'Huangshan', 'Mount Tai',
        'Mount Emei', 'Jiuzhaigou Nature Reserve', 'Lhasa', 'Urumqi', 'Kashgar',
        'Dunhuang', 'Lanzhou', 'Xining', 'Sanya', 'Haikou', 'Hong Kong', 'Macau',
        'Yunnan', 'Sichuan', 'Guangdong', 'Guangxi', 'Fujian', 'Zhejiang', 'Jiangsu',
        'Hunan', 'Hubei', 'Shaanxi', 'Shandong', 'Shanxi', 'Henan', 'Hebei', 'Hainan',
        'Inner Mongolia', 'Xinjiang', 'Tibet', 'Guizhou', 'Jiangxi', 'Anhui',
        'Japan', 'Tokyo', 'Kyoto', 'Osaka', 'Nara', 'Hiroshima', 'Hokkaido', 'Sapporo',
        'Okinawa', 'Fukuoka', 'Mount Fuji',
        'South Korea', 'Seoul', 'Busan', 'Jeju', 'Gyeongju', 'Incheon',
        'Russia', 'Moscow', 'Saint Petersburg', 'Lake Baikal', 'Irkutsk', 'Vladivostok', 'Kazan',
        'Southeast Asia', 'Thailand', 'Bangkok', 'Chiang Mai', 'Phuket',
        'Vietnam', 'Hanoi', 'Ho Chi Minh City', 'Hoi An', 'Da Nang', 'Ha Long Bay',
        'Cambodia', 'Siem Reap', 'Phnom Penh', 'Laos', 'Luang Prabang',
        'Myanmar', 'Yangon', 'Malaysia', 'Kuala Lumpur', 'Penang', 'Langkawi',
        'Singapore', 'Indonesia', 'Bali', 'Jakarta', 'Yogyakarta',
        'Philippines', 'Manila', 'Cebu', 'Boracay', 'Palawan',
        'Europe', 'France', 'Paris', 'Nice', 'Provence', 'Italy', 'Rome', 'Venice',
        'Florence', 'Milan', 'Spain', 'Madrid', 'Barcelona', 'Andalusia',
        'Portugal', 'Lisbon', 'Porto', 'Germany', 'Berlin', 'Munich', 'Bavaria',
        'Netherlands', 'Amsterdam', 'Belgium', 'Brussels', 'Switzerland', 'Zurich',
        'Geneva', 'Interlaken', 'Austria', 'Vienna', 'Salzburg', 'Czech Republic',
        'Prague', 'Hungary', 'Budapest', 'Greece', 'Athens', 'Santorini',
        'Turkey', 'Istanbul', 'United Kingdom', 'London', 'Scotland', 'Edinburgh',
        'Ireland', 'Dublin', 'Denmark', 'Copenhagen', 'Norway', 'Oslo', 'Bergen',
        'Sweden', 'Stockholm', 'Finland', 'Helsinki', 'Iceland', 'Reykjavik',
        'Poland', 'Warsaw', 'Krakow', 'Croatia', 'Dubrovnik', 'Split',
        'Slovenia', 'Ljubljana', 'Romania', 'Bucharest', 'Bulgaria', 'Sofia',
        'Serbia', 'Belgrade', 'Montenegro', 'Kotor', 'Malta',
        'Estonia', 'Tallinn', 'Latvia', 'Riga', 'Lithuania', 'Vilnius'
    ),
    [string]$OutputDir = 'E:\J\Job\AI\voyu\src\main\resources\knowledge\wikivoyage',
    [string]$Site = 'https://en.wikivoyage.org'
)

$ErrorActionPreference = 'Stop'

function Get-WikivoyageExtract {
    param([string]$Title)

    $encoded = [uri]::EscapeDataString($Title)
    $uri = "$Site/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles=$encoded"
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            $response = Invoke-RestMethod -Uri $uri -Headers @{ 'User-Agent' = 'VoyuTravelAgent/0.1 (local RAG builder)' } -TimeoutSec 45
            return $response.query.pages.PSObject.Properties.Value | Select-Object -First 1
        } catch {
            if ($attempt -eq 4) {
                Write-Warning "Failed to fetch $Title after $attempt attempts: $($_.Exception.Message)"
                return $null
            }
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
}

$destinationAliases = @{
    'China' = '中国'
    'Beijing' = '北京'
    'Shanghai' = '上海'
    'Guangzhou' = '广州'
    'Shenzhen' = '深圳'
    'Chengdu' = '成都'
    'Chongqing' = '重庆'
    "Xi'an" = '西安'
    'Hangzhou' = '杭州'
    'Suzhou' = '苏州'
    'Nanjing' = '南京'
    'Wuhan' = '武汉'
    'Changsha' = '长沙'
    'Xiamen' = '厦门'
    'Qingdao' = '青岛'
    'Tianjin' = '天津'
    'Harbin' = '哈尔滨'
    'Shenyang' = '沈阳'
    'Dalian' = '大连'
    'Kunming' = '昆明'
    'Lijiang' = '丽江'
    'Dali' = '大理'
    'Guilin' = '桂林'
    'Yangshuo' = '阳朔'
    'Zhangjiajie' = '张家界'
    'Huangshan' = '黄山'
    'Mount Tai' = '泰山'
    'Mount Emei' = '峨眉山'
    'Jiuzhaigou Nature Reserve' = '九寨沟'
    'Lhasa' = '拉萨'
    'Urumqi' = '乌鲁木齐'
    'Kashgar' = '喀什'
    'Dunhuang' = '敦煌'
    'Lanzhou' = '兰州'
    'Xining' = '西宁'
    'Sanya' = '三亚'
    'Haikou' = '海口'
    'Hong Kong' = '香港'
    'Macau' = '澳门'
    'Yunnan' = '云南'
    'Sichuan' = '四川'
    'Guangdong' = '广东'
    'Guangxi' = '广西'
    'Fujian' = '福建'
    'Zhejiang' = '浙江'
    'Jiangsu' = '江苏'
    'Hunan' = '湖南'
    'Hubei' = '湖北'
    'Shaanxi' = '陕西'
    'Shandong' = '山东'
    'Shanxi' = '山西'
    'Henan' = '河南'
    'Hebei' = '河北'
    'Hainan' = '海南'
    'Inner Mongolia' = '内蒙古'
    'Xinjiang' = '新疆'
    'Tibet' = '西藏'
    'Guizhou' = '贵州'
    'Jiangxi' = '江西'
    'Anhui' = '安徽'
    'Japan' = '日本'
    'Tokyo' = '东京'
    'Kyoto' = '京都'
    'Osaka' = '大阪'
    'Nara' = '奈良'
    'Hiroshima' = '广岛'
    'Hokkaido' = '北海道'
    'Sapporo' = '札幌'
    'Okinawa' = '冲绳'
    'Fukuoka' = '福冈'
    'Mount Fuji' = '富士山'
    'South Korea' = '韩国'
    'Seoul' = '首尔'
    'Busan' = '釜山'
    'Jeju' = '济州岛'
    'Gyeongju' = '庆州'
    'Incheon' = '仁川'
    'Russia' = '俄罗斯'
    'Moscow' = '莫斯科'
    'Saint Petersburg' = '圣彼得堡'
    'Lake Baikal' = '贝加尔湖'
    'Irkutsk' = '伊尔库茨克'
    'Vladivostok' = '海参崴'
    'Kazan' = '喀山'
    'Southeast Asia' = '东南亚'
    'Thailand' = '泰国'
    'Bangkok' = '曼谷'
    'Chiang Mai' = '清迈'
    'Phuket' = '普吉岛'
    'Vietnam' = '越南'
    'Hanoi' = '河内'
    'Ho Chi Minh City' = '胡志明市'
    'Hoi An' = '会安'
    'Da Nang' = '岘港'
    'Ha Long Bay' = '下龙湾'
    'Cambodia' = '柬埔寨'
    'Siem Reap' = '暹粒'
    'Phnom Penh' = '金边'
    'Laos' = '老挝'
    'Luang Prabang' = '琅勃拉邦'
    'Myanmar' = '缅甸'
    'Yangon' = '仰光'
    'Malaysia' = '马来西亚'
    'Kuala Lumpur' = '吉隆坡'
    'Penang' = '槟城'
    'Langkawi' = '兰卡威'
    'Singapore' = '新加坡'
    'Indonesia' = '印度尼西亚'
    'Bali' = '巴厘岛'
    'Jakarta' = '雅加达'
    'Yogyakarta' = '日惹'
    'Philippines' = '菲律宾'
    'Manila' = '马尼拉'
    'Cebu' = '宿务'
    'Boracay' = '长滩岛'
    'Palawan' = '巴拉望'
    'Europe' = '欧洲'
    'France' = '法国'
    'Paris' = '巴黎'
    'Nice' = '尼斯'
    'Provence' = '普罗旺斯'
    'Italy' = '意大利'
    'Rome' = '罗马'
    'Venice' = '威尼斯'
    'Florence' = '佛罗伦萨'
    'Milan' = '米兰'
    'Spain' = '西班牙'
    'Madrid' = '马德里'
    'Barcelona' = '巴塞罗那'
    'Andalusia' = '安达卢西亚'
    'Portugal' = '葡萄牙'
    'Lisbon' = '里斯本'
    'Porto' = '波尔图'
    'Germany' = '德国'
    'Berlin' = '柏林'
    'Munich' = '慕尼黑'
    'Bavaria' = '巴伐利亚'
    'Netherlands' = '荷兰'
    'Amsterdam' = '阿姆斯特丹'
    'Belgium' = '比利时'
    'Brussels' = '布鲁塞尔'
    'Switzerland' = '瑞士'
    'Zurich' = '苏黎世'
    'Geneva' = '日内瓦'
    'Interlaken' = '因特拉肯'
    'Austria' = '奥地利'
    'Vienna' = '维也纳'
    'Salzburg' = '萨尔茨堡'
    'Czech Republic' = '捷克'
    'Prague' = '布拉格'
    'Hungary' = '匈牙利'
    'Budapest' = '布达佩斯'
    'Greece' = '希腊'
    'Athens' = '雅典'
    'Santorini' = '圣托里尼'
    'Turkey' = '土耳其'
    'Istanbul' = '伊斯坦布尔'
    'United Kingdom' = '英国'
    'London' = '伦敦'
    'Scotland' = '苏格兰'
    'Edinburgh' = '爱丁堡'
    'Ireland' = '爱尔兰'
    'Dublin' = '都柏林'
    'Denmark' = '丹麦'
    'Copenhagen' = '哥本哈根'
    'Norway' = '挪威'
    'Oslo' = '奥斯陆'
    'Bergen' = '卑尔根'
    'Sweden' = '瑞典'
    'Stockholm' = '斯德哥尔摩'
    'Finland' = '芬兰'
    'Helsinki' = '赫尔辛基'
    'Iceland' = '冰岛'
    'Reykjavik' = '雷克雅未克'
    'Poland' = '波兰'
    'Warsaw' = '华沙'
    'Krakow' = '克拉科夫'
    'Croatia' = '克罗地亚'
    'Dubrovnik' = '杜布罗夫尼克'
    'Split' = '斯普利特'
    'Slovenia' = '斯洛文尼亚'
    'Ljubljana' = '卢布尔雅那'
    'Romania' = '罗马尼亚'
    'Bucharest' = '布加勒斯特'
    'Bulgaria' = '保加利亚'
    'Sofia' = '索非亚'
    'Serbia' = '塞尔维亚'
    'Belgrade' = '贝尔格莱德'
    'Montenegro' = '黑山'
    'Kotor' = '科托尔'
    'Malta' = '马耳他'
    'Estonia' = '爱沙尼亚'
    'Tallinn' = '塔林'
    'Latvia' = '拉脱维亚'
    'Riga' = '里加'
    'Lithuania' = '立陶宛'
    'Vilnius' = '维尔纽斯'
}

function Split-Sections {
    param([string]$Text)

    $sections = New-Object System.Collections.Generic.List[object]
    $matches = [regex]::Matches($Text, '(?m)^==+\s*(.+?)\s*==+\s*$')

    if ($matches.Count -eq 0) {
        $sections.Add([pscustomobject]@{ Title = 'Overview'; Body = $Text.Trim() })
        return $sections
    }

    $lead = $Text.Substring(0, $matches[0].Index).Trim()
    if ($lead) {
        $sections.Add([pscustomobject]@{ Title = 'Overview'; Body = $lead })
    }

    for ($i = 0; $i -lt $matches.Count; $i++) {
        $start = $matches[$i].Index + $matches[$i].Length
        $end = if ($i -lt $matches.Count - 1) { $matches[$i + 1].Index } else { $Text.Length }
        $title = $matches[$i].Groups[1].Value.Trim()
        $body = $Text.Substring($start, $end - $start).Trim()
        if ($body) {
            $sections.Add([pscustomobject]@{ Title = $title; Body = $body })
        }
    }

    return $sections
}

function Clean-SectionBody {
    param(
        [string]$Body,
        [int]$MaxParagraphs = 3
    )

    $paragraphs = $Body -split "(\r?\n){2,}" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^(===|By |From |Getting there and away:|Travel time:|Per-person|Call \+|updated )' }

    $cleaned = $paragraphs | Select-Object -First $MaxParagraphs
    return (($cleaned -join "`n`n") -replace '[ \t]{2,}', ' ').Trim()
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$keepSections = @(
    'Overview', 'Regions', 'Cities', 'Other destinations', 'Districts', 'Understand', 'Get in', 'Get around',
    'See', 'Do', 'Buy', 'Eat', 'Drink', 'Sleep', 'Stay safe', 'Go next'
)

foreach ($page in $Pages) {
    $doc = Get-WikivoyageExtract -Title $page
    if ($null -eq $doc -or [string]::IsNullOrWhiteSpace($doc.extract)) {
        Write-Warning "Skipped $page because no Wikivoyage extract was returned."
        continue
    }

    $sections = Split-Sections -Text $doc.extract
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("# $($doc.title)")
    if ($destinationAliases.ContainsKey($page)) {
        [void]$builder.AppendLine("Chinese destination aliases: $($destinationAliases[$page]), $page, $($doc.title)")
    }
    [void]$builder.AppendLine()

    foreach ($section in $sections) {
        if ($section.Title -notin $keepSections) {
            continue
        }
        $maxParagraphs = if ($section.Title -eq 'Overview') { 4 } else { 2 }
        $body = Clean-SectionBody -Body $section.Body -MaxParagraphs $maxParagraphs
        if (-not $body) {
            continue
        }
        [void]$builder.AppendLine("## $($section.Title)")
        [void]$builder.AppendLine($body)
        [void]$builder.AppendLine()
    }

    $slug = ($doc.title.ToLowerInvariant() -replace '[^a-z0-9]+', '-') -replace '(^-|-$)', ''
    $target = Join-Path $OutputDir "$slug.md"
    Set-Content -Path $target -Value $builder.ToString().Trim() -Encoding UTF8
    Write-Output "Saved $target"
    Start-Sleep -Milliseconds 450
}
