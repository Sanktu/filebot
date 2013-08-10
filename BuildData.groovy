import  org.tukaani.xz.*

// ------------------------------------------------------------------------- //


def sortRegexList(path) {
	def set = new TreeSet(String.CASE_INSENSITIVE_ORDER)
	new File(path).eachLine('UTF-8'){
		// check if regex compiles
		set += java.util.regex.Pattern.compile(it.trim()).pattern()
	}
	
	def out = set.join('\n').saveAs(path)
	println "$out\n$out.text\n"
}


// sort and check shared regex collections
sortRegexList("website/data/release-groups.txt")
sortRegexList("website/data/query-blacklist.txt")
sortRegexList("website/data/exclude-blacklist.txt")
sortRegexList("website/data/series-mappings.txt")


// ------------------------------------------------------------------------- //


def reviews = []
new File('reviews.csv').eachLine('UTF-8'){ def s = it.split(';', 3); reviews << [user: s[0], date: s[1], text: s[2].replaceAll(/^["]|["]$/, '').replaceAll(/["]{2}/, '"')] }
reviews = reviews.sort{ it.date }

def json = new groovy.json.JsonBuilder()
json.call(reviews as List)
json.toPrettyString().saveAs('website/reviews.json')
println "Reviews: " + reviews.size()


// ------------------------------------------------------------------------- //


def movies_out  = new File("website/data/movies.txt")
def thetvdb_out = new File("website/data/thetvdb.txt")
def anidb_out   = new File("website/data/anidb.txt")

def pack(file, lines) {
	new File(file.parentFile, file.name + '.xz').withOutputStream{ out ->
		new XZOutputStream(out, new LZMA2Options(LZMA2Options.PRESET_DEFAULT)).withWriter('UTF-8'){ writer ->
			lines.each{ writer.append(it).append('\n') }
		}
	}
}


// ------------------------------------------------------------------------- //

// BUILD movies.txt.gz
def omdb = new TreeSet({ a, b -> a[0].compareTo(b[0]) } as Comparator)
new File('omdb.txt').eachLine('Windows-1252'){
	def line = it.split(/\t/)
	
	if (line.length > 11 && line[0] ==~ /\d+/) {
		def imdbid = line[1].substring(2).toInteger()
		def name = line[2]
		def year = line[3].toInteger()
		def runtime = line[5]
		def rating = tryQuietly{ line[11].toFloat() } ?: 0
		def votes = tryQuietly{ line[12].replaceAll(/\D/, '').toInteger() } ?: 0
		
		if ((year >= 1970 && (runtime =~ /h/ || votes >= 200) && rating >= 1 && votes >= 50) || (votes >= 2000)) {
			line = line*.replaceAll(/\s+/, ' ')*.trim()
			omdb << [imdbid, name, year]
		}
	}
}
omdb = omdb.findAll{ it[0] <= 9999999 && it[1] =~ /^[A-Z0-9]/ && it[1] =~ /[\p{Alpha}]{3}/ && it[1].length() >= 4}.collect{ [it[0].pad(7), it[1], it[2]] }

// save movie data
def movies = omdb.findAll{ it.size() >= 3 && !it[1].startsWith('"') }
def movieSorter = new TreeMap(String.CASE_INSENSITIVE_ORDER)
movies.each{ movieSorter.put([it[1], it[2], it[0]].join('\t'), it) }
movies = movieSorter.values().collect{ it.join('\t') }

pack(movies_out, movies)
println "Movie Count: " + movies.size()

// sanity check
if (movies.size() < 50000) { throw new Exception('Movie index sanity failed') }

// ------------------------------------------------------------------------- //

// BUILD thetvdb-index.gz
def tvdb = new HashMap()
def tvdb_txt = new File('tvdb.txt')
new File('tvdb.txt').eachLine{
	def line = it.split('\t', 5).toList()
	tvdb.put(line[0] as Integer, [line[0] as Integer, line[1], line[2], line[3], line[4] as Integer])
}

def tvdb_updates = new File('updates_all.xml').text.xml.'**'.Series.findResults{ s -> tryQuietly{ [id:s.id.text() as Integer, time:s.time.text() as Integer] } }
tvdb_updates.each{ update ->
	if (tvdb[update.id] == null || update.time > tvdb[update.id][4]) {
		try {
			retry(2, 500) {
				def xml = new URL("http://thetvdb.com/api/BA864DEE427E384A/series/${update.id}/en.xml").fetch().text.xml
				def imdbid = xml.'**'.IMDB_ID.text()
				def tvdb_name = xml.'**'.SeriesName.text()
				def imdb_name = _guarded{
					if (imdbid =~ /tt(\d+)/) {
						def dom = IMDb.parsePage(IMDb.getMoviePageLink(imdbid.match(/tt(\d+)/) as int).toURL())
						return net.sourceforge.tuned.XPathUtilities.selectString("//META[@property='og:title']/@content", dom)
					}
				}
				def data = [update.id, imdbid ?: '', tvdb_name ?: '', imdb_name ?: '', update.time]
				tvdb.put(update.id, data)
				println "Update $update => $data"
			}
		}
		catch(Throwable e) {
			def data = [update.id, '', '', '', update.time]
			tvdb.put(update.id, data)
			println "Update $update => $data"
		}
	}
}

tvdb.values().findResults{ it.join('\t') }.join('\n').saveAs(tvdb_txt)


def thetvdb_index = []
tvdb.values().each{
	def n1 = it[2].trim()
	def n2 = it[3].replaceAll(/^(?i)(The|A)\s/, '').replaceAll(/\s&\s/, ' and ').replaceAll(/\([^\)]*\)$/, '').trim()
	if (similarity(n1,n2) < 1) {
		thetvdb_index << [it[0], n1]
		thetvdb_index << [it[0], n2]
	} else {
		thetvdb_index << [it[0], n1]
	}
}


thetvdb_index = thetvdb_index.findResults{ [it[0] as Integer, it[1].replaceAll(/\s+/, ' ').trim()] }.findAll{ !(it[1] =~ /(?i:duplicate)/ || it[1] =~ /\d{6,}/ || it[1].startsWith('*') || it[1].endsWith('*') || it[1].length() <= 3) }
thetvdb_index = thetvdb_index.sort(new Comparator() {
	int compare(a, b) { a[0] <=> b[0] }
})

// join and sort
def thetvdb_txt = thetvdb_index.findResults{ [it[0].pad(6), it[1].trim()].join('\t') }
pack(thetvdb_out, thetvdb_txt)
println "TheTVDB Index: " + thetvdb_txt.size()

// sanity check
if (thetvdb_txt.size() < 30000) { throw new Exception('TheTVDB index sanity failed') }



// BUILD anidb-index.gz
def anidb = new net.sourceforge.filebot.web.AnidbClient(null, 0).getAnimeTitles()

def anidb_index = anidb.findResults{ [it.getAnimeId(), it.getPrimaryTitle(), it.getEnglishTitle()] }

// join and sort
def anidb_txt = anidb_index.findResults{ [it[0].pad(5), it[1] ?: '', it[2] == null || it[2].equals(it[1]) ? '' : it[2]]*.replaceAll(/\s+/, ' ')*.trim().join('\t').replaceAll(/['`´‘’ʻ]+/, /'/) }.sort().unique()
pack(anidb_out, anidb_txt)
println "AniDB Index: " + anidb_txt.size()

// sanity check
if (anidb_txt.size() < 5000) { throw new Exception('AniDB index sanity failed') }
