package com.dentapinos.dataguard.storage;

import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Интерфейс для работы с хранилищем резервных копий.
 * <p>
 * Предоставляет абстракцию для операций сохранения, загрузки, удаления и управления
 * файлами резервных копий, организованных по уровням хранения ({@link BackupTier}).
 * Поддерживает идемпотентность операций для безопасного повторения при сбоях.
 * </p>
 * @see BackupTier Уровень хранения резервной копии (DAILY, WEEKLY, MONTHLY и т.д.)
 * @see BackupFileInfo Метаданные резервной копии (имя, размер, время создания)
 */
public interface BackupStorage {

    /**
     * Сохраняет файл в указанном уровне хранения и базе данных.
     * <p>
     * <strong>ВАЖНО: реализация НЕ ДОЛЖНА закрывать переданный InputStream.</strong>
     * Закрытие потока — ответственность вызывающего кода.
     * </p>
     * <p>
     * <strong>Контракт идемпотентности:</strong>
     * Реализация должна перезаписывать существующий файл, используя
     * StandardCopyOption.REPLACE_EXISTING. Это обеспечивает:
     * <ul>
     *   <li>Повторный вызов с тем же fileName не приводит к ошибке</li>
     *   <li>Гарантирует актуальное состояние файла</li>
     *   <li>Позволяет безопасно повторять операции в случае сбоев</li>
     * </ul>
     * </p>
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @param content    поток данных для сохранения
     * @throws IOException если операция сохранения не удалась
     */
    void save(@Nullable BackupTier tier, String database, String fileName, InputStream content) throws IOException;

    /**
     * Загружает файл как InputStream.
     * <p>
     * <strong>ВАЖНО: вызывающий код обязан закрыть возвращённый InputStream.</strong>
     * </p>
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @return InputStream для чтения содержимого файла
     * @throws IOException если операция загрузки не удалась, (файл не найден, ошибка доступа и т.д.)
     */
    InputStream load(@Nullable BackupTier tier, String database, String fileName) throws IOException;

    /**
     * Удаляет файл из хранилища.
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @throws IOException если операция удаления не удалась, (файл не найден, ошибка доступа и т.д.)
     */
    void delete(@Nullable BackupTier tier, String database, String fileName) throws IOException;

    /**
     * Возвращает список имён файлов для указанного уровня хранения и базы данных.
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @return Список имён файлов резервных копий
     * @throws IOException если операция получения списка не удалась
     */
    List<String> list(@Nullable BackupTier tier, String database) throws IOException;

    /**
     * Возвращает время создания файла.
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @return FileTime — время создания файла
     * @throws IOException если операция получения времени не удалась
     */
    FileTime getCreationTime(@Nullable BackupTier tier, String database, String fileName) throws IOException;

    /**
     * Возвращает размер файла в байтах.
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @return Размер файла в байтах
     * @throws IOException если операция получения размера не удалась
     */
    long getFileSize(@Nullable BackupTier tier, String database, String fileName) throws IOException;

    /**
     * Возвращает метаданные файла резервной копии.
     * <p>
     * По умолчанию собирает информацию на основе getCreationTime и getFileSize.
     * </p>
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @return {@link BackupFileInfo} с метаданными файла
     * @throws IOException если операция получения метаданных не удалась
     */
    default BackupFileInfo getFileInfo(@Nullable BackupTier tier, String database, String fileName) throws IOException {
        FileTime creationTime = getCreationTime(tier, database, fileName);
        long size = getFileSize(tier, database, fileName);
        return new BackupFileInfo(
                fileName,
                tier,
                database,
                creationTime.toInstant(),
                size
        );
    }

    /**
     * Упрощённый метод: оборачивает load в Optional.
     * <p>
     * <strong>ВАЖНО: вызывающий код обязан закрыть InputStream, если он есть.</strong>
     * <p>
     * Исключения IOException проглатываются, вместо них возвращается Optional.empty().
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @param fileName   имя файла резервной копии
     * @return Optional с InputStream, если файл найден и доступен; Optional.empty() в противном случае
     */
    @SuppressWarnings("unused")
    default Optional<InputStream> tryLoad(@Nullable BackupTier tier, String database, String fileName) {
        try {
            return Optional.of(load(tier, database, fileName));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Упрощённый метод: возвращает информацию о всех файлах для базы в указанном уровне хранения.
     * <p>
     * При ошибке получения отдельных атрибутов (creationTime/size) запись пропускается,
     * ошибка логируется в реализации.
     * </p>
     *
     * @param tier       уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @return Список метаданных файлов ({@link BackupFileInfo})
     * @throws IOException если операция получения списка не удалась
     */
    default List<BackupFileInfo> listWithInfo(@Nullable BackupTier tier, String database) throws IOException {
        return list(tier, database).stream()
                .map(fileName -> {
                    try {
                        return getFileInfo(tier, database, fileName);
                    } catch (IOException e) {
                        // Реализация может залогировать ошибку
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Копирует резервный файл между уровнями хранения (promotion).
     * <p>
     * <strong>Контракт идемпотентности:</strong>
     * Операция копирования должна быть идемпотентной:
     * <ul>
     *   <li>Если файл с таким именем уже существует в целевом tier, он будет перезаписан</li>
     *   <li>Это позволяет безопасно повторять операции продвижения (promotion)</li>
     *   <li>Реализация использует ATOMIC_MOVE или REPLACE_EXISTING</li>
     * </ul>
     * </p>
     *
     * @param fileName   имя ZIP-файла для копирования
     * @param fromTier   исходный уровень хранения (DAILY, WEEKLY и т.д.)
     * @param toTier     целевой уровень хранения (WEEKLY, MONTHLY и т.д.)
     * @param database   имя базы данных (часть пути)
     * @return Путь к скопированному файлу (относительный путь в виде "tier/filename")
     * @throws IOException если операция копирования не удалась
     */
    String copy(String fileName, BackupTier fromTier, BackupTier toTier, String database) throws IOException;
}
