package ru.mchs.uavalert.data

/**
 * Оперативное предупреждение об угрозе БПЛА, полученное с сайта МЧС России.
 */
data class UavAlert(
    val id: String,            // уникальный идентификатор (ссылка/URL новости)
    val title: String,         // заголовок, например "В области объявлена угроза БПЛА"
    val region: String,        // регион (область/край/республика) из тега МЧС
    val text: String,          // краткое описание / текст сводки
    val timestamp: Long,       // время публикации, мс
    val url: String            // ссылка на источник (mchs.gov.ru)
)
