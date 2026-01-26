-- ============================================================
-- V1: Начальная схема Device Manager
-- ============================================================
-- 
-- Таблицы:
-- - organizations: Организации (клиенты системы)
-- - vehicles: Транспортные средства
-- - sensor_profiles: Профили датчиков
-- - devices: GPS трекеры
-- ============================================================

-- ============================================================
-- ОРГАНИЗАЦИИ
-- ============================================================
CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    inn VARCHAR(12),
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    address TEXT,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Europe/Moscow',
    max_devices INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Индексы
CREATE INDEX idx_organizations_email ON organizations(email);
CREATE INDEX idx_organizations_is_active ON organizations(is_active);

-- Комментарии
COMMENT ON TABLE organizations IS 'Организации - клиенты системы мониторинга';
COMMENT ON COLUMN organizations.inn IS 'ИНН организации (10 или 12 цифр)';
COMMENT ON COLUMN organizations.max_devices IS 'Максимальное количество устройств по тарифу';

-- ============================================================
-- ТРАНСПОРТНЫЕ СРЕДСТВА
-- ============================================================
CREATE TABLE vehicles (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    vehicle_type VARCHAR(50) NOT NULL DEFAULT 'Car',
    license_plate VARCHAR(20),
    vin VARCHAR(17),
    brand VARCHAR(100),
    model VARCHAR(100),
    year INT,
    color VARCHAR(50),
    fuel_type VARCHAR(50),
    fuel_tank_capacity DOUBLE PRECISION,
    icon_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Ограничения
    CONSTRAINT chk_vehicle_type CHECK (vehicle_type IN ('Car', 'Truck', 'Bus', 'Motorcycle', 'Trailer', 'Special', 'Other')),
    CONSTRAINT chk_year CHECK (year IS NULL OR (year >= 1900 AND year <= 2100)),
    CONSTRAINT chk_fuel_tank CHECK (fuel_tank_capacity IS NULL OR fuel_tank_capacity > 0)
);

-- Индексы
CREATE INDEX idx_vehicles_organization ON vehicles(organization_id);
CREATE INDEX idx_vehicles_license_plate ON vehicles(license_plate) WHERE license_plate IS NOT NULL;
CREATE INDEX idx_vehicles_vin ON vehicles(vin) WHERE vin IS NOT NULL;

-- Комментарии
COMMENT ON TABLE vehicles IS 'Транспортные средства';
COMMENT ON COLUMN vehicles.vehicle_type IS 'Тип ТС: Car, Truck, Bus, Motorcycle, Trailer, Special, Other';
COMMENT ON COLUMN vehicles.fuel_tank_capacity IS 'Объём топливного бака в литрах';

-- ============================================================
-- ПРОФИЛИ ДАТЧИКОВ
-- ============================================================
CREATE TABLE sensor_profiles (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    sensors JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Индексы
CREATE INDEX idx_sensor_profiles_organization ON sensor_profiles(organization_id);

-- Комментарии
COMMENT ON TABLE sensor_profiles IS 'Профили датчиков для интерпретации IO-элементов';
COMMENT ON COLUMN sensor_profiles.sensors IS 'JSON массив с конфигурацией датчиков';

-- ============================================================
-- УСТРОЙСТВА (GPS ТРЕКЕРЫ)
-- ============================================================
CREATE TABLE devices (
    id BIGSERIAL PRIMARY KEY,
    imei VARCHAR(15) NOT NULL UNIQUE,
    name VARCHAR(255),
    protocol VARCHAR(50) NOT NULL DEFAULT 'Teltonika',
    status VARCHAR(50) NOT NULL DEFAULT 'Active',
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    vehicle_id BIGINT REFERENCES vehicles(id),
    sensor_profile_id BIGINT REFERENCES sensor_profiles(id),
    phone_number VARCHAR(20),
    firmware_version VARCHAR(50),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Ограничения
    CONSTRAINT chk_imei CHECK (imei ~ '^\d{15}$'),
    CONSTRAINT chk_protocol CHECK (protocol IN ('Teltonika', 'Wialon', 'Ruptela', 'NavTelecom', 'Galileo', 'Custom')),
    CONSTRAINT chk_status CHECK (status IN ('Active', 'Inactive', 'Suspended', 'Deleted'))
);

-- Индексы
CREATE INDEX idx_devices_imei ON devices(imei);
CREATE INDEX idx_devices_organization ON devices(organization_id);
CREATE INDEX idx_devices_vehicle ON devices(vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX idx_devices_status ON devices(status);
CREATE INDEX idx_devices_protocol ON devices(protocol);
CREATE INDEX idx_devices_last_seen ON devices(last_seen_at) WHERE last_seen_at IS NOT NULL;

-- Частичный индекс для активных устройств (часто используется)
CREATE INDEX idx_devices_active ON devices(organization_id, imei) WHERE status = 'Active';

-- Комментарии
COMMENT ON TABLE devices IS 'GPS трекеры';
COMMENT ON COLUMN devices.imei IS '15-значный IMEI устройства (глобально уникальный)';
COMMENT ON COLUMN devices.protocol IS 'Протокол связи: Teltonika, Wialon, Ruptela, NavTelecom, Galileo, Custom';
COMMENT ON COLUMN devices.status IS 'Статус: Active, Inactive, Suspended, Deleted';
COMMENT ON COLUMN devices.last_seen_at IS 'Время последнего подключения к Connection Manager';

-- ============================================================
-- ТРИГГЕРЫ
-- ============================================================

-- Функция обновления updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Триггеры для каждой таблицы
CREATE TRIGGER update_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_vehicles_updated_at
    BEFORE UPDATE ON vehicles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_sensor_profiles_updated_at
    BEFORE UPDATE ON sensor_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- ТЕСТОВЫЕ ДАННЫЕ
-- ============================================================

-- Тестовая организация
INSERT INTO organizations (name, email, timezone, max_devices) VALUES
    ('Тестовая организация', 'test@example.com', 'Europe/Moscow', 100);

-- Тестовые ТС
INSERT INTO vehicles (organization_id, name, vehicle_type, license_plate) VALUES
    (1, 'Тестовый автомобиль 1', 'Car', 'А001АА777'),
    (1, 'Тестовый грузовик', 'Truck', 'В002ВВ777');

-- Тестовые устройства
INSERT INTO devices (imei, name, protocol, organization_id, vehicle_id) VALUES
    ('860719020025346', 'Teltonika FMB920 #1', 'Teltonika', 1, 1),
    ('860719020025347', 'Wialon GPS #1', 'Wialon', 1, 2),
    ('860719020025348', 'Ruptela Pro 5', 'Ruptela', 1, NULL);
