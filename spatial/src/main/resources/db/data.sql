INSERT INTO location_point (name, coordinates) VALUES 
('Wroclaw Rynek', ST_SetSRID(ST_MakePoint(17.032, 51.110), 4326)),
('Warszawa Centrum', ST_SetSRID(ST_MakePoint(21.012, 52.230), 4326));